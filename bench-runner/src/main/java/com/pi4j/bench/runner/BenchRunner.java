package com.pi4j.bench.runner;

import com.pi4j.bench.common.Ansi;
import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.runner.report.HtmlReport;
import com.pi4j.bench.runner.report.ReportCommand;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/// The whole benchmark matrix as ordinary Java — this command replaces the root
/// `benchAll` task graph (plan §4.3). Sequencing is statements, "always unload" is a
/// `finally`, and each lane runs in its own forked JVM (the v3/v4 `pi4j-core` GAVs never
/// share this classpath — plan Appendix A). Gradle only resolved the per-lane classpaths
/// (build/lanes/*.properties) and launched this one JVM.
///
/// Packaged three ways from the same code: `./gradlew :bench-runner:run`, a self-contained
/// `bench-runner-all.jar`, or a GraalVM native binary. With `--bundle-root` it runs from a
/// staged, scp'd bundle whose lane specs carry paths relative to the bundle root.
///
/// Order (unchanged): envCheck → JMH lanes (self-load their mocks per trial) → loadMocks
/// (shared gpio+i2c) → memory → latency → mergeReports, with unloadMocks in a `finally`.
/// Deliberately sequential: CPU-pinned measurement lanes must never overlap.
@Command(
        name = "bench",
        mixinStandardHelpOptions = true,
        version = "pi4j-bench 1.0",
        subcommands = { ReportCommand.class },
        description = "Run the Pi4J V4(FFM)-vs-V3 benchmark matrix (hot path, lifecycle, latency, memory).",
        footerHeading = "%nBenchmarks (lanes) — pick with --lanes/--only; all run by default:%n",
        footer = {
                "  @|bold v4-jmh|@            V4/FFM hot path: GPIO, I2C, SPI, PWM, lifecycle (jmh)",
                "  @|bold v3-jmh|@            V3 hot path: gpiod/linuxfs GPIO, linuxfs I2C (jmh)",
                "  @|bold memory-fixed|@      V4 native memory, fixed path 4.0.2 — RSS/NMT, no leak",
                "  @|bold memory-issue628|@   Reproduces the #628 native leak on 4.0.1 (RSS climb)",
                "  @|bold warmup-v4|@         FFM time-to-steady: jit + aot [+ leyden w/ --leyden-jdk]",
                "  @|bold ffm-vthreads|@      E4: virtual-thread carrier pinning on blocking downcalls",
                "  @|bold ffm-safepoint|@     E5: time-to-safepoint with a critical() native call in flight",
                "  @|bold ffm-cleaner|@       E6: Arena.ofAuto() Cleaner reclamation lag vs ofConfined",
                "  @|bold latency-v4|@        Edge->listener latency/jitter, HDR percentiles @ 200 Hz",
                "  @|bold latency-v4-native|@ latency-v4 as a GraalVM image (--native, prebuilt)",
                "",
                "@|bold Examples:|@",
                "  ./bench                            # whole matrix, mock lane, G1",
                "  ./bench --quick                    # fast smoke test",
                "  ./bench --lanes v4-jmh,v3-jmh      # only the JMH hot-path lanes",
                "  ./bench --only latency-v4 --gc zgc # one lane under ZGC",
                "  ./bench --gc-matrix g1,zgc,parallel   # once per collector -> <ts>/<gc>/",
                "  ./bench --preset gc-sweep          # same, via a bench.config preset",
                "  ./bench --preset latency-pressure  # latency x {g1,zgc}, background alloc",
                "  ./bench --only latency-v4 --gc-pressure --gc-pressure-mbps 300",
                "  ./bench --lane hw --cpus 2,3       # real peripherals, forks pinned 2-3",
                "  ./bench --only latency-v4-native --native   # GraalVM native latency lane",
                "  ./bench --dry-run --preset gc-sweep   # print the plan; change nothing",
                "",
                "Every run writes a dark-theme report.html next to its results.",
                "  ./bench report                     # compare the latest 2 runs (HTML)",
                "  ./bench report --last 4            # compare the latest 4",
                "  ./bench report <run-dir>           # single-run report -> report.html",
                "  ./bench report -h                  # report options",
                "" })
public final class BenchRunner implements Callable<Integer> {

    // Lane execution order by spec name; anything unlisted sorts to the end.
    private static final List<String> ORDER = List.of(
            "v4-jmh", "v3-jmh", "memory-fixed", "memory-issue628",
            "warmup-v4", "warmup-v4-aot", "warmup-v4-leyden",
            "ffm-vthreads", "ffm-safepoint", "ffm-cleaner",
            "latency-v4", "latency-v4-native", "latency-v3");

    // Block E deep-dive lanes now route through real Pi4J FFM calls against the shared i2c mock
    // (so their numbers are directly comparable/mergeable with the v4 lane), so they run in the
    // shared-mock phase like latency/memory. E5 (safepoint, usleep) needs no mock but rides along.
    private static final List<String> FFM_DEEPDIVE = List.of("ffm-vthreads", "ffm-safepoint", "ffm-cleaner");

    // Nullable so we can tell "flag given" from "flag absent" and apply CLI > config >
    // default precedence (config = run.* keys in bench.config). All defaults live in call().
    @Option(names = "--lane", paramLabel = "mock|hw",
            description = "Peripheral lane (config: run.lane; default: mock).")
    String lane;

    @Option(names = "--quick", negatable = true, description = "1 JMH fork + short latency/memory runs (config: run.quick).")
    Boolean quick;

    @Option(names = "--gc", paramLabel = "g1|zgc|parallel|serial|shenandoah|epsilon",
            description = "GC applied to EVERY measurement fork (config: run.gc; default: g1).")
    String gc;

    @Option(names = "--gc-matrix", split = ",", paramLabel = "g1,zgc[,…]",
            description = "Run the WHOLE matrix once per collector, each into its own <ts>/<gc>/ dir "
                    + "(config: run.gcs). Overrides --gc.")
    List<String> gcMatrix = new ArrayList<>();

    @Option(names = "--preset", paramLabel = "<name>",
            description = "Apply a preset.<name>.* block from bench.config (gcs/lanes/gc-pressure/… ; "
                    + "config: run.preset). Explicit CLI flags still win.")
    String preset;

    @Option(names = "--gc-pressure", negatable = true,
            description = "Run a background allocator during the latency lane (config: run.gc-pressure).")
    Boolean gcPressure;

    @Option(names = "--gc-pressure-mbps", paramLabel = "<mb/s>",
            description = "Target churn rate for --gc-pressure (config: run.gc-pressure-mbps; default: 200).")
    Integer gcPressureMbps;

    @Option(names = "--jfr", negatable = true, description = "Attach JFR to the latency lane (config: run.jfr).")
    Boolean jfr;

    @Option(names = "--profilers", split = ",", paramLabel = "gc[,…]",
            description = "Extra JMH profilers (config: run.profilers; e.g. gc → gc.alloc.rate.norm).")
    List<String> profilers = new ArrayList<>();

    @Option(names = "--cpus", paramLabel = "2,3",
            description = "taskset -c core list for every measurement fork (config: run.cpus; default: none).")
    String cpus;

    @Option(names = "--jhiccup", paramLabel = "<jar>",
            description = "Attach the jHiccup agent (jar path) to the latency lane (config: run.jhiccup).")
    String jhiccup;

    @Option(names = {"--only", "--lanes"}, split = ",", paramLabel = "name[,…]",
            description = "Run just these lanes, in THIS order (config: run.lanes; default: all).")
    List<String> only = new ArrayList<>();

    @Option(names = "--native", negatable = true,
            description = "Include the GraalVM native-image latency lane if its binary is present (config: run.native).")
    Boolean nativeImage;

    @Option(names = "--leyden-jdk", paramLabel = "<dir>",
            description = "Leyden EA JDK for the FFM-warmup Leyden variant (config: run.leyden.jdk).")
    String leydenJdk;

    @Option(names = "--dry-run", description = "Print the plan (env check + fork commands); change nothing.")
    boolean dryRun;

    @Option(names = "--bundle-root", paramLabel = "<dir>",
            description = "Run from a staged bundle: resolve lane specs/mocks relative to this dir.")
    Path bundleRoot;

    @Option(names = "--config", paramLabel = "<file>",
            description = "Runtime config (JDK path + mock name/address map). Default: bench.config next to the binary.")
    Path configFile;

    @Option(names = "--jdk", paramLabel = "<dir>",
            description = "JDK home used to fork the measurement lanes (overrides config; else PATH).")
    String jdk;

    @Option(names = "--mocks-dir", paramLabel = "<dir>",
            description = "Where to materialise/build the embedded mock drivers (default: next to the binary).")
    Path mocksDir;

    @Option(names = "--rebuild-mocks", description = "Re-extract the mock sources and recompile the .ko.")
    boolean rebuildMocks;

    public static void main(String[] args) {
        // Auto-width: wrap help to the real terminal width (falls back to 80 when piped),
        // so the lanes/examples footer stays readable on a normal-width console.
        System.exit(new CommandLine(new BenchRunner()).setUsageHelpAutoWidth(true).execute(args));
    }

    private static final List<String> GC_NAMES = List.of("g1", "zgc", "parallel", "serial", "shenandoah", "epsilon");

    // Per-run snapshot stamp — LOCAL time (was UTC), so the folder name matches the wall
    // clock of the operator reading it.
    private static final DateTimeFormatter LOCAL_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    @Override
    public Integer call() throws Exception {
        var arch = System.getProperty("os.arch");
        // Bundle root: explicit --bundle-root, else auto-detected when a `lanes/` dir sits
        // next to the artifact (or in the CWD) — so a bare jar/binary run from inside the
        // bundle Just Works without the launcher passing --bundle-root.
        var resolvedBundle = resolveBundleRoot();
        var bundle = resolvedBundle != null;
        var root = bundle
                ? resolvedBundle
                : Path.of(System.getProperty("bench.root", ".")).toAbsolutePath().normalize();
        var lanesDir = bundle
                ? root.resolve("lanes")
                : Path.of(System.getProperty("bench.lanesDir", root.resolve("build/lanes").toString()));
        var baseDir = bundle ? root : null; // rebase relative spec paths only from a bundle

        // Resolve bench.config first, then merge CLI > preset > config > default for every
        // option. A preset (preset.<name>.* in bench.config) is an intermediate layer: it
        // overrides plain run.* values but any explicit CLI flag still wins over it.
        var runConfig = RunConfig.resolve(configFile, bundle ? root : null);
        var opts = runConfig.options();
        var presetName = firstNonBlank(preset, opts.preset(), null);
        Map<String, String> pv = Map.of();
        if (presetName != null) {
            pv = runConfig.presets().get(presetName);
            if (pv == null) {
                System.err.println("--preset / run.preset '" + presetName + "' not found; known: "
                        + runConfig.presets().keySet());
                return 2;
            }
        }
        var eLane = firstNonBlank(lane, pv.get("lane"), opts.lane(), "mock");
        var eGc = firstNonBlank(gc, pv.get("gc"), opts.gc(), "g1").toLowerCase();
        var eQuick = firstNonNull(quick, boolOf(pv.get("quick")), opts.quick(), false);
        var eJfr = firstNonNull(jfr, boolOf(pv.get("jfr")), opts.jfr(), false);
        var eCpus = firstNonBlank(cpus, pv.get("cpus"), opts.cpus(), null);
        var eJhiccup = firstNonBlank(jhiccup, pv.get("jhiccup"), opts.jhiccup(), null);
        var eProfilers = !profilers.isEmpty() ? List.copyOf(profilers)
                : pv.containsKey("profilers") ? csv(pv.get("profilers"))
                : opts.profilers() != null ? opts.profilers() : List.<String>of();
        var eLanes = !only.isEmpty() ? List.copyOf(only)
                : pv.containsKey("lanes") ? csv(pv.get("lanes"))
                : opts.lanes() != null ? opts.lanes() : List.<String>of();
        var eNative = firstNonNull(nativeImage, boolOf(pv.get("native")), opts.nativeImage(), false);
        var eLeyden = firstNonBlank(leydenJdk, pv.get("leyden.jdk"), opts.leydenJdk(), null);
        var eGcPressure = firstNonNull(gcPressure, boolOf(pv.get("gc-pressure")), opts.gcPressure(), false);
        var eGcPressureMbps = firstNonNull(gcPressureMbps, intOf(pv.get("gc-pressure-mbps")),
                opts.gcPressureMbps(), 200);

        // The GC sweep: --gc-matrix > preset.gcs > run.gcs > the single resolved --gc.
        // Every lane runs once per collector; a one-element list is the plain single-GC run.
        var eGcs = !gcMatrix.isEmpty() ? lower(gcMatrix)
                : pv.containsKey("gcs") ? lower(csv(pv.get("gcs")))
                : opts.gcs() != null ? lower(opts.gcs())
                : List.of(eGc);
        for (var g : eGcs) {
            if (!GC_NAMES.contains(g)) {
                System.err.println("gc '" + g + "' must be one of " + GC_NAMES);
                return 2;
            }
        }
        // cfg0 carries every option except the collector, which is set per sweep iteration.
        var cfg0 = new Config(eLane, eQuick, eGcs.getFirst(), eJfr, eProfilers, eCpus, eJhiccup,
                eLanes, eNative, eLeyden, eGcPressure, eGcPressureMbps, dryRun);

        // Mock drivers: dev builds use the in-repo src trees; a bundle or a bare binary
        // materialises the embedded mock sources next to where the binary lives and builds
        // the .ko there once (reused). `extracted` is null only in a dev (Gradle) run.
        var devRun = !bundle && System.getProperty("bench.lanesDir") != null;
        var mockHome = mocksDir != null ? mocksDir : (bundle ? root : artifactDir());
        var extracted = devRun && mocksDir == null ? null : MockBuilder.prepare(mockHome, rebuildMocks, cfg0.dryRun());
        var mockResources = extracted != null
                ? extracted.resolve("v4/src/test/resources")
                : root.resolve("bench-v4/src/test/resources");
        // One directory PER RUN: results/<arch>/<lane>/<local-timestamp>/ — nothing from a
        // prior invocation is ever reused or overwritten. A GC sweep nests one <gc>/ subdir
        // per collector inside it; the run-level manifests live at the top of the run dir.
        var ts = LOCAL_TS.format(Instant.now());
        var runDir = root.resolve("results").resolve(arch).resolve(cfg0.lane()).resolve(ts);
        if (!cfg0.dryRun()) Files.createDirectories(runDir);

        var jdkHome = (jdk != null && !jdk.isBlank()) ? jdk : runConfig.jdk();

        var env = EnvManifest.captureSafe();
        var effectiveJdk = jdkHome != null ? jdkHome : System.getProperty("java.home");

        // Header — the whole run's context, one aligned coloured block.
        System.out.println();
        System.out.println(Ansi.banner("pi4j-bench") + "  "
                + Ansi.dim("V4(FFM) vs V3 · hot-path · lifecycle · latency · memory"));
        kv("host", "%s  %s".formatted(
                Ansi.value(env.cpuModel()),
                Ansi.dim("%d cpus · %s · %s · %s".formatted(
                        env.cpus(), humanBytes(env.totalMemBytes()), env.arch(), env.os()))));
        kv("jdk", "%s   %s %s".formatted(
                Ansi.value(env.jdk()), Ansi.dim("forks" + Ansi.ARROW),
                effectiveJdk != null ? effectiveJdk : Ansi.dim("(PATH)")));
        kv("config", "lane=%s quick=%s gc=%s cpus=%s%s%s".formatted(
                Ansi.value(cfg0.lane()), b(cfg0.quick()), Ansi.value(String.join(",", eGcs)),
                cfg0.cpus() == null ? Ansi.dim("none") : Ansi.value(cfg0.cpus()),
                cfg0.gcPressure() ? "  " + Ansi.yellow("[gc-pressure " + cfg0.gcPressureMbps() + " MB/s]") : "",
                cfg0.dryRun() ? "  " + Ansi.yellow("[dry-run]") : ""));
        if (presetName != null) kv("preset", Ansi.value(presetName));
        kv("run", Ansi.dim(root.relativize(runDir).toString()));
        kv("mocks", "%s   %s %s".formatted(
                String.join(", ", runConfig.ifaces()), Ansi.dim("addressing"),
                Ansi.dim(runConfig.laneProps().toString())));
        if (bundle) kv("bundle", Ansi.dim(root.toString()));
        System.out.println();

        System.out.println(Ansi.banner("environment check"));
        EnvCheck.run(cfg0);
        writeEnvManifest(runDir, env, cfg0);

        // Order: the requested lane list (config run.lanes / --lanes) wins and is preserved;
        // otherwise the canonical ORDER. The JMH-before-MAIN mock-lifecycle split still holds,
        // so a custom order is honoured WITHIN each phase.
        var laneOrder = cfg0.lanes().isEmpty() ? ORDER : cfg0.lanes();
        var specs = LaneSpec.loadAll(lanesDir, baseDir).stream()
                .filter(s -> cfg0.wants(s.name()))
                .filter(s -> nativeReady(s, cfg0)) // opt-in + binary present for NATIVE lanes
                .sorted(Comparator.comparingInt(s -> {
                    int i = laneOrder.indexOf(s.name());
                    return i < 0 ? laneOrder.size() : i;
                }))
                .toList();
        if (specs.isEmpty()) throw new IllegalStateException("no matching lane specs in " + lanesDir);

        var forker = new Forker(cfg0.cpus(), jdkHome, cfg0.dryRun());
        var mocks = new MockLifecycle(mockResources, cfg0, runConfig);
        var laneProps = runConfig.laneProps();
        if (cfg0.isMock()) printSudoersHelp(mockResources, extracted);

        // GC sweep: run the whole lane set once per collector. A single-element list is just
        // the ordinary single-GC run; only then do outputs sit directly under the run dir.
        var outcomes = new ArrayList<LaneOutcome>();
        var sweep = eGcs.size() > 1;
        for (var g : eGcs) {
            var cfg = cfg0.withGc(g);
            var gcDir = sweep ? runDir.resolve(g) : runDir;
            if (sweep) {
                System.out.printf("%n%s  %s%n", Ansi.banner("collector " + g),
                        Ansi.dim("sweep " + (eGcs.indexOf(g) + 1) + "/" + eGcs.size()));
            }
            outcomes.addAll(runLanes(forker, mocks, specs, cfg, gcDir, laneProps, extracted));
        }

        new ReportMerger(root, arch, cfg0).merge(runDir, ts, eGcs);

        // Phase 7 — a self-contained dark-theme HTML report next to the results, every run.
        if (!cfg0.dryRun()) {
            try {
                var html = HtmlReport.writeSingle(runDir);
                if (html != null) {
                    System.out.println("  " + Ansi.ok("report " + Ansi.ARROW + " "
                            + Ansi.cyan(root.relativize(html).toString())));
                }
            } catch (Exception e) {
                System.out.println("  " + Ansi.warn("report generation failed: " + e.getMessage()));
            }
        }

        var failed = outcomes.stream().filter(o -> !o.ok()).map(LaneOutcome::name).toList();
        printSummary(outcomes, runDir, root);
        if (!failed.isEmpty()) {
            System.out.println(Ansi.fail("bench-runner: lanes failed: " + String.join(", ", failed)));
            return 1;
        }
        return 0;
    }

    /// Run the full lane set once, for a single collector, into `resultsDir` (JMH hot-path
    /// lanes first with self-loaded mocks, then the shared-mock latency/memory/warmup lanes
    /// with an always-unload `finally`). Called once per collector in a `--gc-matrix` sweep.
    private List<LaneOutcome> runLanes(Forker forker, MockLifecycle mocks, List<LaneSpec> specs,
            Config cfg, Path resultsDir, Map<String, String> laneProps, Path extracted) throws Exception {
        if (!cfg.dryRun()) Files.createDirectories(resultsDir);
        var outcomes = new ArrayList<LaneOutcome>();
        var jmh = specs.stream().filter(s -> s.type() == LaneSpec.Type.JMH).count();

        // Phase A/B — JMH lanes first; they self-manage their mocks per trial.
        System.out.printf("%n%s  %s%n", Ansi.banner("JMH hot-path lanes"),
                Ansi.dim(jmh + " lane(s) · self-loaded mocks"));
        for (var s : specs) {
            if (s.type() == LaneSpec.Type.JMH) outcomes.add(run(forker, s, cfg, resultsDir, laneProps, extracted));
        }

        // Blocks C/D/E (+ the NATIVE/warmup lanes) need the shared mocks pre-loaded; unload
        // always, even on failure. Skip the load/unload entirely when no selected lane consumes
        // them (e.g. a JMH-only run) so those runs need no root/sudoers.
        var sharedMock = specs.stream()
                .filter(s -> s.type() != LaneSpec.Type.JMH)
                .toList();
        if (sharedMock.isEmpty()) {
            return outcomes;
        }
        System.out.printf("%n%s  %s%n", Ansi.banner("latency, memory, warmup & FFM lanes"),
                Ansi.dim(sharedMock.size() + " lane(s) · shared mocks"));
        mocks.load();
        try {
            for (var s : sharedMock) {
                if (s.name().equals("warmup-v4")) {
                    outcomes.addAll(runWarmup(forker, s, cfg, resultsDir, laneProps));
                } else {
                    outcomes.add(run(forker, s, cfg, resultsDir, laneProps, extracted));
                }
            }
        } finally {
            mocks.unload();
        }
        return outcomes;
    }

    /// One lane's result — name, kind, exit code and wall-clock — collected for the summary.
    private record LaneOutcome(String name, LaneSpec.Type type, int exit, long millis) {
        boolean ok() {
            return exit == 0;
        }
    }

    /// Aligned `key  value` line under a banner (dim bullet + fixed-width cyan key).
    private static void kv(String key, String value) {
        System.out.printf("  %s %s  %s%n", Ansi.dim(Ansi.BULLET), Ansi.cyan(pad(key, 7)), value);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static String b(boolean v) {
        return v ? Ansi.green("true") : Ansi.dim("false");
    }

    private static String humanBytes(long bytes) {
        if (bytes <= 0) return "?";
        var units = new String[] {"B", "KiB", "MiB", "GiB", "TiB"};
        int i = (int) (Math.log(bytes) / Math.log(1024));
        i = Math.min(i, units.length - 1);
        return "%.1f %s".formatted(bytes / Math.pow(1024, i), units[i]);
    }

    private static String humanMillis(long millis) {
        if (millis < 1000) return millis + "ms";
        long s = millis / 1000;
        return s < 60 ? "%d.%01ds".formatted(s, (millis % 1000) / 100) : "%dm%02ds".formatted(s / 60, s % 60);
    }

    /// Final per-lane table (status glyph + name + wall-clock) and the results path.
    private static void printSummary(List<LaneOutcome> outcomes, Path resultsDir, Path root) {
        System.out.printf("%n%s%n", Ansi.banner("summary"));
        if (outcomes.isEmpty()) {
            System.out.println("  " + Ansi.dim("no lanes ran"));
            return;
        }
        long total = 0;
        int pass = 0;
        for (var o : outcomes) {
            total += o.millis();
            if (o.ok()) pass++;
            var status = o.ok() ? Ansi.green(Ansi.CHECK) : Ansi.red(Ansi.CROSS);
            System.out.printf("  %s  %s  %s%s%n", status, Ansi.value(pad(o.name(), 18)),
                    Ansi.dim(pad(o.type().toString().toLowerCase(), 7)),
                    Ansi.dim(humanMillis(o.millis())));
        }
        var headline = pass == outcomes.size()
                ? Ansi.okText(Ansi.CHECK + " all " + outcomes.size() + " lanes passed")
                : Ansi.errText(Ansi.CROSS + " " + (outcomes.size() - pass) + "/" + outcomes.size() + " lanes failed");
        System.out.printf("  %s  %s%n", headline, Ansi.dim("in " + humanMillis(total)));
        var rel = root.relativize(resultsDir);
        System.out.printf("  %s results %s %s%n", Ansi.dim(Ansi.BULLET), Ansi.ARROW, Ansi.cyan(rel.toString()));
    }

    /// CLI value (if given) wins, then the config value, then the built-in default.
    private static String firstNonBlank(String cli, String config, String def) {
        if (cli != null && !cli.isBlank()) return cli;
        if (config != null && !config.isBlank()) return config;
        return def;
    }

    /// CLI > preset > config > default, for string options (blank == unset).
    private static String firstNonBlank(String cli, String preset, String config, String def) {
        if (cli != null && !cli.isBlank()) return cli;
        if (preset != null && !preset.isBlank()) return preset;
        if (config != null && !config.isBlank()) return config;
        return def;
    }

    private static boolean firstNonNull(Boolean cli, Boolean config, boolean def) {
        if (cli != null) return cli;
        if (config != null) return config;
        return def;
    }

    /// CLI > preset > config > default, for boxed booleans.
    private static boolean firstNonNull(Boolean cli, Boolean preset, Boolean config, boolean def) {
        if (cli != null) return cli;
        if (preset != null) return preset;
        if (config != null) return config;
        return def;
    }

    /// CLI > preset > config > default, for boxed ints.
    private static int firstNonNull(Integer cli, Integer preset, Integer config, int def) {
        if (cli != null) return cli;
        if (preset != null) return preset;
        if (config != null) return config;
        return def;
    }

    /// Parse a preset/CLI boolean string (true/1/yes → true); null stays null ("unset").
    private static Boolean boolOf(String v) {
        if (v == null || v.isBlank()) return null;
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }

    private static Integer intOf(String v) {
        return (v == null || v.isBlank()) ? null : Integer.valueOf(v.trim());
    }

    private static List<String> csv(String v) {
        return (v == null || v.isBlank()) ? List.of() : List.of(v.split("\\s*,\\s*"));
    }

    private static List<String> lower(List<String> in) {
        return in.stream().map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).toList();
    }

    /// Resolve the bundle root, or null for a dev (Gradle) run. Explicit `--bundle-root`
    /// wins; a Gradle run is flagged by `bench.lanesDir`; otherwise treat the artifact's
    /// dir (or the CWD) as the bundle root when it contains a `lanes/` dir.
    private Path resolveBundleRoot() {
        if (bundleRoot != null) return bundleRoot.toAbsolutePath().normalize();
        if (System.getProperty("bench.lanesDir") != null) return null; // dev / Gradle :run
        var artifact = artifactDir();
        if (Files.isDirectory(artifact.resolve("lanes"))) return artifact;
        var cwd = Path.of(".").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("lanes"))) return cwd;
        return null;
    }

    /// Where the running artifact lives — used as the default home for the materialised
    /// mock tree. Native image: the executable's dir; fat jar: the jar's dir; else CWD.
    private static Path artifactDir() {
        var cmd = ProcessHandle.current().info().command();
        if (cmd.isPresent()) {
            var p = Path.of(cmd.get());
            if (!p.getFileName().toString().equals("java")) return p.toAbsolutePath().getParent();
        }
        try {
            var src = BenchRunner.class.getProtectionDomain().getCodeSource();
            if (src != null) {
                var loc = Path.of(src.getLocation().toURI());
                return Files.isRegularFile(loc) ? loc.toAbsolutePath().getParent() : loc.toAbsolutePath();
            }
        } catch (Exception ignore) {
            // fall through
        }
        return Path.of(".").toAbsolutePath();
    }

    /// The mock setup/clean scripts insmod via sudo. Print the exact NOPASSWD sudoers line
    /// for the resolved script dir(s) and drop a copy-pasteable `sudoers.snippet` beside the
    /// mocks so the operator can grant passwordless access once, for the current user.
    private static void printSudoersHelp(Path mockResources, Path extracted) {
        var user = System.getProperty("user.name");
        var dirs = new ArrayList<String>();
        dirs.add(mockResources.toAbsolutePath() + "/");
        if (extracted != null) {
            var v3 = extracted.resolve("v3/src/test/resources");
            if (!v3.equals(mockResources)) dirs.add(v3.toAbsolutePath() + "/");
        }
        var lines = String.join("\n", dirs.stream().map(d -> user + " ALL=(ALL) NOPASSWD: " + d).toList());
        System.out.printf("""
                mocks: insmod/rmmod run via sudo. To allow this without a password, add to
                sudoers (run `sudo visudo`), for the current user:
                %s
                %n""", lines);
        if (extracted != null && Files.exists(extracted)) { // not in a dry run
            try {
                Files.writeString(extracted.getParent().resolve("sudoers.snippet"), lines + "\n");
            } catch (Exception ignore) {
                // best-effort convenience file
            }
        }
    }

    /// Build and fork one lane's `java` command line. JMH lanes run with their CWD at the
    /// materialised mock tree (`<mocks>/<flavor>`) so BaseSetup finds `src/test/resources`;
    /// in a dev run (`extracted == null`) they use the spec's in-repo working dir.
    private static LaneOutcome run(Forker forker, LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps, Path extracted) {
        System.out.printf("%n%s %s %s%n", Ansi.dim("┌─ lane"), Ansi.heading(s.name()),
                Ansi.dim("(" + s.type().toString().toLowerCase() + ")"));
        var cmd = switch (s.type()) {
            case JMH -> jmhCommand(forker, s, cfg, resultsDir, laneProps);
            case MAIN -> mainCommand(forker, s, cfg, resultsDir, laneProps);
            case NATIVE -> nativeCommand(s, cfg, resultsDir, laneProps);
        };
        var workingDir = (s.type() == LaneSpec.Type.JMH && extracted != null)
                ? extracted.resolve(flavorOf(s.name()))
                : s.workingDir();
        var t0 = System.nanoTime();
        var exit = forker.fork(cmd, workingDir);
        var millis = (System.nanoTime() - t0) / 1_000_000;
        var tag = exit == 0 ? Ansi.ok("done") : Ansi.fail("exit " + exit);
        System.out.printf("%s %s %s%n", Ansi.dim("└─"), tag, Ansi.dim("(" + humanMillis(millis) + ")"));
        return new LaneOutcome(s.name(), s.type(), exit, millis);
    }

    /// A NATIVE (GraalVM AOT) lane only runs when opted in (--native / run.native) AND its
    /// per-arch binary was actually built; otherwise it's silently dropped so the default
    /// matrix works on hosts without GraalVM. Non-NATIVE lanes always pass.
    private boolean nativeReady(LaneSpec s, Config cfg) {
        if (s.type() != LaneSpec.Type.NATIVE) return true;
        if (!cfg.nativeImage()) return false;
        var present = s.binary() != null && Files.isExecutable(Path.of(s.binary()));
        if (!present) {
            System.out.println("  " + Ansi.warn("native lane " + s.name()
                    + " requested but its AOT binary is missing — build it (:native-image:nativeCompile)"));
        }
        return present;
    }

    /// A NATIVE lane runs its self-contained AOT binary directly (no `java`, no classpath).
    /// GraalVM images accept `-D…` system properties at runtime, so addressing/results flow
    /// through the same way; the GC and `--enable-native-access` JVM flags don't apply.
    private static List<String> nativeCommand(LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps) {
        var cmd = new ArrayList<String>();
        cmd.add(s.binary());
        s.sysProps().forEach((k, v) -> cmd.add("-D" + k + "=" + v));
        laneProps.forEach((k, v) -> cmd.add("-D" + k + "=" + v));
        cmd.add("-Dresults.dir=" + resultsDir.toAbsolutePath());
        cmd.addAll(s.args());
        cmd.add(cfg.quick() ? "--warmup-seconds=2" : "--warmup-seconds=5");
        cmd.add(cfg.quick() ? "--measure-seconds=3" : "--measure-seconds=30");
        return cmd;
    }

    /// The FFM-warmup lane expands into up to three forked variants that share one spec:
    /// baseline JIT, JDK 25 AOT cache (JEP 483: train then measure), and — when a Leyden EA
    /// JDK is configured — the same run on that JDK. Their curves overlay for the slide.
    private static List<LaneOutcome> runWarmup(Forker forker, LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps) {
        var outcomes = new ArrayList<LaneOutcome>();
        outcomes.add(runWarmupVariant(forker, s, cfg, resultsDir, laneProps,
                "warmup-v4", forker.java(), List.of(), "jit"));
        outcomes.addAll(runWarmupAot(forker, s, cfg, resultsDir, laneProps));
        if (cfg.leydenJdk() != null && !cfg.leydenJdk().isBlank()) {
            var leydenJava = Path.of(cfg.leydenJdk(), "bin", "java").toString();
            outcomes.add(runWarmupVariant(forker, s, cfg, resultsDir, laneProps,
                    "warmup-v4-leyden", leydenJava, List.of(), "leyden"));
        } else {
            System.out.println("  " + Ansi.dim(Ansi.BULLET
                    + " warmup: Leyden variant skipped (set run.leyden.jdk / --leyden-jdk)"));
        }
        return outcomes;
    }

    /// JDK 25 AOT cache (JEP 483): a short training fork writes the cache at exit
    /// (`-XX:AOTCacheOutput`), then the measured fork consumes it (`-XX:AOTCache`). If the
    /// training fork fails (older JDK, no AOT support), the AOT variant is skipped.
    private static List<LaneOutcome> runWarmupAot(Forker forker, LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps) {
        var aotFile = resultsDir.resolve("warmup-v4.aot").toAbsolutePath().toString();
        System.out.printf("%n%s %s %s%n", Ansi.dim("┌─ lane"), Ansi.heading("warmup-v4-aot"),
                Ansi.dim("(warmup · AOT train)"));
        var train = warmupBase(s, cfg, resultsDir, laneProps, forker.java(),
                List.of("-XX:AOTCacheOutput=" + aotFile), "train");
        train.add("--iterations=20000");
        var t0 = System.nanoTime();
        var trainExit = forker.fork(train, s.workingDir());
        System.out.printf("%s %s %s%n", Ansi.dim("└─"),
                trainExit == 0 ? Ansi.ok("cache written") : Ansi.fail("train exit " + trainExit),
                Ansi.dim("(" + humanMillis((System.nanoTime() - t0) / 1_000_000) + ")"));
        if (trainExit != 0) {
            System.out.println("  " + Ansi.warn("warmup: AOT training failed — skipping the AOT-cache variant"));
            return List.of();
        }
        return List.of(runWarmupVariant(forker, s, cfg, resultsDir, laneProps,
                "warmup-v4-aot", forker.java(), List.of("-XX:AOTCache=" + aotFile), "aot"));
    }

    /// One warmup fork: `<java> <jvmArgs> <extraJvm> <gc> -D… -cp … WarmupRunner --label=…`.
    private static LaneOutcome runWarmupVariant(Forker forker, LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps, String laneName, String javaExe, List<String> extraJvm, String label) {
        System.out.printf("%n%s %s %s%n", Ansi.dim("┌─ lane"), Ansi.heading(laneName), Ansi.dim("(warmup)"));
        var cmd = warmupBase(s, cfg, resultsDir, laneProps, javaExe, extraJvm, label);
        if (cfg.quick()) cmd.add("--iterations=20000");
        var t0 = System.nanoTime();
        var exit = forker.fork(cmd, s.workingDir());
        var millis = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%s %s %s%n", Ansi.dim("└─"),
                exit == 0 ? Ansi.ok("done") : Ansi.fail("exit " + exit),
                Ansi.dim("(" + humanMillis(millis) + ")"));
        return new LaneOutcome(laneName, LaneSpec.Type.MAIN, exit, millis);
    }

    private static ArrayList<String> warmupBase(LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps, String javaExe, List<String> extraJvm, String label) {
        var cmd = new ArrayList<String>();
        cmd.add(javaExe);
        cmd.addAll(s.jvmArgs());          // --enable-native-access=ALL-UNNAMED
        cmd.addAll(extraJvm);             // AOT cache flags, if any
        cmd.addAll(List.of(cfg.gcFlag().split(" ")));
        s.sysProps().forEach((k, v) -> cmd.add("-D" + k + "=" + v));
        laneProps.forEach((k, v) -> cmd.add("-D" + k + "=" + v));
        cmd.add("-Dresults.dir=" + resultsDir.toAbsolutePath());
        cmd.add("-cp");
        cmd.add(s.classpath());
        cmd.add(s.mainClass());
        cmd.add("--label=" + label);
        return cmd;
    }

    /// v4-jmh → v4, v3-jmh → v3 (the mock-tree flavour for a JMH lane).
    private static String flavorOf(String laneName) {
        var i = laneName.indexOf("-jmh");
        return i > 0 ? laneName.substring(0, i) : laneName;
    }

    /// `java -cp <cp> org.openjdk.jmh.Main -f N -rf json -rff …/<name>.json -jvmArgsAppend "…"`.
    /// Per-JVM flags (native access, heap, pi4j.library.path) go to JMH's *forked*
    /// measurement JVMs via -jvmArgsAppend, not the controlling process.
    private static List<String> jmhCommand(Forker forker, LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps) {
        var cmd = new ArrayList<String>();
        cmd.add(forker.java());
        cmd.add("-cp");
        cmd.add(s.classpath());
        cmd.add(s.mainClass()); // org.openjdk.jmh.Main
        cmd.add("-f");
        cmd.add(cfg.quick() ? "1" : "3");
        cmd.add("-wf");
        cmd.add("0");
        cmd.add("-foe");
        cmd.add("true");
        cfg.profilers().forEach(p -> {
            cmd.add("-prof");
            cmd.add(p);
        });
        cmd.add("-rf");
        cmd.add("json");
        cmd.add("-rff");
        cmd.add(resultsDir.resolve(s.name() + ".json").toAbsolutePath().toString());
        var append = new ArrayList<>(s.jvmArgs());
        // The selected GC applies to JMH's forked measurement JVMs too (not just latency).
        append.addAll(List.of(cfg.gcFlag().split(" ")));
        s.sysProps().forEach((k, v) -> append.add("-D" + k + "=" + v));
        // Peripheral addressing from bench.config → the forked measurement JVMs (BenchProps).
        laneProps.forEach((k, v) -> append.add("-D" + k + "=" + v));
        cmd.add("-jvmArgsAppend");
        cmd.add(String.join(" ", append));
        return cmd;
    }

    /// `java <jvmArgs> -Dk=v… -Dresults.dir=… -cp <cp> <MainClass> <args>` for the custom
    /// LatencyRunner/MemoryRunner. GC/JFR/jHiccup are the orchestrator's launch choices.
    private static List<String> mainCommand(Forker forker, LaneSpec s, Config cfg, Path resultsDir,
            Map<String, String> laneProps) {
        var cmd = new ArrayList<String>();
        cmd.add(forker.java());
        cmd.addAll(s.jvmArgs());
        s.sysProps().forEach((k, v) -> cmd.add("-D" + k + "=" + v));
        // Mock/HW addressing from bench.config — the lane reads bench.* (e.g. MemoryRunner
        // honours bench.i2c.bus / bench.i2c.device), falling back to the compiled defaults.
        laneProps.forEach((k, v) -> cmd.add("-D" + k + "=" + v));
        cmd.add("-Dresults.dir=" + resultsDir.toAbsolutePath());

        // The selected GC applies to every MAIN lane (memory + latency), not latency only.
        cmd.addAll(List.of(cfg.gcFlag().split(" ")));

        // E5: the authoritative time-to-safepoint record is the JVM's own safepoint log (the
        // plan's §6 method) — capture it in the ffm/ result subdir so plot_slides.py can parse TTSP.
        if (s.name().equals("ffm-safepoint")) {
            var ffmDir = resultsDir.resolve("ffm");
            try {
                Files.createDirectories(ffmDir); // -Xlog needs the parent dir to exist at JVM start
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            cmd.add("-Xlog:safepoint=info:file=" + ffmDir.resolve("ffm-safepoint-vmlog.txt")
                    + ":uptime,level,tags");
        }

        var isLatency = s.name().startsWith("latency");
        if (isLatency) {
            if (cfg.jfr()) {
                cmd.add("-XX:StartFlightRecording=filename=" + resultsDir.resolve(
                        s.name() + "-" + cfg.gc() + ".jfr") + ",settings=profile,dumponexit=true");
            }
            if (cfg.jhiccup() != null) {
                cmd.add("-javaagent:" + cfg.jhiccup() + "=-d,0,-i,1000,-l,"
                        + resultsDir.resolve("hiccup-" + s.name() + "-" + cfg.gc() + ".hlog"));
            }
        }

        cmd.add("-cp");
        cmd.add(s.classpath());
        cmd.add(s.mainClass());
        cmd.addAll(s.args()); // fixed lane args, e.g. --mode=fixed

        // Quick smoke vs full run — shorter measurement windows / fewer ops.
        if (isLatency) {
            cmd.add(cfg.quick() ? "--warmup-seconds=2" : "--warmup-seconds=30");
            cmd.add(cfg.quick() ? "--measure-seconds=3" : "--measure-seconds=60");
            // GC-pressure: a background allocator churns during the measure window so the
            // tail reflects the collector under load (LatencyRunner --gc-pressure[-mbps]).
            if (cfg.gcPressure()) {
                cmd.add("--gc-pressure");
                cmd.add("--gc-pressure-mbps=" + cfg.gcPressureMbps());
            }
        } else if (s.name().startsWith("memory") && cfg.quick()) {
            cmd.add("--total-ops=100000");
            cmd.add("--snapshot-every=10000");
        } else if (s.name().startsWith("ffm-") && cfg.quick()) {
            // Block E deep-dive lanes honour --quick (shorter windows / fewer task counts).
            cmd.add("--quick");
        }
        return cmd;
    }

    /// Append-only env manifest next to results (ports the old stampEnvManifest task),
    /// reusing bench-common's [EnvManifest#captureSafe].
    private static void writeEnvManifest(Path resultsDir, EnvManifest env, Config cfg) throws Exception {
        if (cfg.dryRun()) return;
        var sha = Sh.capture("git", "rev-parse", "--short", "HEAD");
        Files.writeString(resultsDir.resolve("env-manifest.json"), """
                {
                  "arch": "%s",
                  "lane": "%s",
                  "jdk": "%s",
                  "os": "%s",
                  "cpus": %d,
                  "cpuModel": "%s",
                  "totalMemBytes": %d,
                  "gitSha": "%s"
                }
                """.formatted(env.arch(), cfg.lane(), env.jdk(), env.os(), env.cpus(),
                env.cpuModel(), env.totalMemBytes(),
                sha.exit() == 0 && !sha.out().isBlank() ? sha.out() : "nogit"));
    }
}
