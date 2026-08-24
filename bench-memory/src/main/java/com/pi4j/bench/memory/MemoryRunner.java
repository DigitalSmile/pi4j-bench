package com.pi4j.bench.memory;

import com.pi4j.Pi4J;
import com.pi4j.bench.common.Ansi;
import com.pi4j.bench.common.BenchProps;
import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.NmtSampler;
import com.pi4j.bench.common.RssSampler;
import com.pi4j.context.Context;
import com.pi4j.io.i2c.I2C;
import com.pi4j.io.i2c.I2CConfigBuilder;
import com.pi4j.io.i2c.I2CImplementation;
import com.pi4j.plugin.ffm.providers.i2c.FFMI2CProviderImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Block D — RSS/NMT trajectory over 1 M FFM I2C reads (plan §6.4.1).
///
/// The code is version-agnostic: it drives the plain I2C API and the *runtime*
/// classpath decides the memory behaviour. `--mode=fixed` runs against pi4j 4.0.2
/// (per-call `Arena.ofConfined()` → footprint stays flat); `--mode=issue628` runs
/// against pre-fix 4.0.1 (shared `Arena.ofAuto()` → the ioctl buffers accumulate and
/// RSS ramps). Both are Maven Central releases, swapped by the two run tasks in
/// `build.gradle.kts`; the mode here only labels the result — see [MemoryReport].
///
/// Compiled against 4.0.x (`I2CConfigBuilder.newInstance(Context)`), *not* the
/// 5.0.0-SNAPSHOT the hot-path lane uses (which dropped the arg) — the #628 repro is
/// deliberately pinned to the released 4.0.x line so it reproduces from Central alone.
public final class MemoryRunner {

    // Addressing comes from bench.config via -Dbench.i2c.* (the orchestrator passes it
    // through); falls back to the mock defaults (bench i2c-setup.sh → /dev/i2c-99, 0x1C).
    private static final int I2C_BUS = BenchProps.intProp("bench.i2c.bus", 99);
    private static final int I2C_DEVICE = BenchProps.hexProp("bench.i2c.device", 0x1C);
    private static final int REGISTER = BenchProps.hexProp("bench.i2c.register", 0xFF);

    void main(String[] args) throws IOException {
        var mode = parseMode(args);
        long totalOps = parseLong(args, "--total-ops=", 1_000_000L);
        long snapshotEvery = parseLong(args, "--snapshot-every=", 50_000L);
        var pi4jVersion = System.getProperty("pi4j.version", "unknown");
        var env = EnvManifest.captureSafe();
        System.out.println(Ansi.banner("memory · v4-" + mode));
        System.out.println("  " + Ansi.dim(Ansi.BULLET) + " " + Ansi.dim(env.toString()));
        System.out.printf("  %s pi4j=%s · %s FFM I2C reads · snapshot every %s%s%n",
                Ansi.dim(Ansi.BULLET), Ansi.value(pi4jVersion),
                Ansi.value("%,d".formatted(totalOps)), Ansi.value("%,d".formatted(snapshotEvery)),
                mode.equals("issue628") ? "  " + Ansi.yellow("[#628 pre-fix: RSS ramps]") : "  " + Ansi.dim("[fixed: flat footprint]"));

        Context pi4j;
        I2C i2c;
        try {
            pi4j = Pi4J.newContextBuilder().add(new FFMI2CProviderImpl()).build();
            i2c = pi4j.create(I2CConfigBuilder.newInstance(pi4j)
                    .bus(I2C_BUS)
                    .device(I2C_DEVICE)
                    .i2cImplementation(I2CImplementation.DIRECT));
        } catch (RuntimeException e) {
            System.err.printf("""
                    FATAL: could not open the mock I2C device (/dev/i2c-%d): %s
                    Block D needs the i2c mock loaded and readable by this JVM.
                    Load it (loadMocks / i2c-setup.sh) and run under tools/pin-env.sh (root),
                    or grant the node: sudo chmod a+rw /dev/i2c-%d
                    %n""", I2C_BUS, e.getMessage(), I2C_BUS);
            System.exit(2);
            return; // unreachable, keeps the compiler happy about pi4j/i2c being set
        }

        var nmt = new NmtSampler();
        var trajectory = new ArrayList<MemoryReport.Snapshot>();
        var buffer = new byte[3];
        long baselineRss = 0;
        try {
            for (long op = 0; op <= totalOps; op++) {
                if (op % snapshotEvery == 0) {
                    var snap = new MemoryReport.Snapshot(op, RssSampler.vmRssKib(), nmt.committedKib());
                    trajectory.add(snap);
                    if (op == 0) baselineRss = snap.rssKib();
                    long deltaKib = snap.rssKib() - baselineRss;
                    System.out.printf("    %s %s  VmRSS=%s  NMT=%s  %s%n",
                            Ansi.dim(Ansi.GEAR),
                            Ansi.dim(pct(op, totalOps)),
                            Ansi.value("%,7d kB".formatted(snap.rssKib())),
                            Ansi.dim("%,d kB".formatted(snap.nmtCommittedKib())),
                            deltaBar(deltaKib));
                }
                i2c.readRegister(REGISTER, buffer); // the leaking native path in #628
            }
        } finally {
            pi4j.shutdown();
        }

        writeResults(mode, pi4jVersion, totalOps, snapshotEvery, env, trajectory);
    }

    private void writeResults(String mode, String pi4jVersion, long totalOps, long snapshotEvery,
                              EnvManifest env, List<MemoryReport.Snapshot> trajectory) throws IOException {
        var first = trajectory.getFirst();
        var last = trajectory.getLast();
        var report = new MemoryReport(mode, pi4jVersion, totalOps, snapshotEvery,
                first.rssKib(), last.rssKib(), last.rssKib() - first.rssKib(),
                first.nmtCommittedKib(), last.nmtCommittedKib(),
                last.nmtCommittedKib() - first.nmtCommittedKib(),
                env, trajectory);

        System.out.printf("%n  %s  %s%n", Ansi.okText(Ansi.CHECK + " memory"), Ansi.dim("v4-" + mode));
        deltaRow("RSS", report.rssStartKib(), report.rssEndKib(), report.rssDeltaKib());
        deltaRow("NMT", report.nmtStartKib(), report.nmtEndKib(), report.nmtDeltaKib());

        var dir = resultsDir();
        Files.createDirectories(dir);
        var base = "v4-" + mode;
        exportCsv(dir.resolve(base + "-trajectory.csv"), trajectory);
        report.writeJson(dir.resolve(base + ".json"));
        System.out.println("  " + Ansi.ok("results " + Ansi.ARROW + " "
                + Ansi.cyan(dir.resolve(base + ".{json,-trajectory.csv}").toString())));
    }

    /// `RSS  start → end kB  (Δ +N)` with the delta green when flat/shrinking, red when it grew.
    private static void deltaRow(String label, long startKib, long endKib, long deltaKib) {
        var delta = "Δ %,+d kB".formatted(deltaKib);
        System.out.printf("    %s  %s %s %s   %s%n",
                Ansi.cyan(label),
                Ansi.value("%,d kB".formatted(startKib)), Ansi.dim(Ansi.ARROW),
                Ansi.value("%,d kB".formatted(endKib)),
                deltaKib > 1024 ? Ansi.red(delta) : Ansi.green(delta));
    }

    private static String pct(long op, long total) {
        return "%3d%%".formatted(total == 0 ? 100 : Math.round(100.0 * op / total));
    }

    /// A tiny sparkline of RSS growth from the baseline — one block per ~4 MiB, capped.
    private static String deltaBar(long deltaKib) {
        if (deltaKib <= 0) return Ansi.dim("Δ0");
        int blocks = Math.min(24, (int) (deltaKib / 4096));
        var bar = "█".repeat(blocks); // █
        var label = "Δ+%,d kB".formatted(deltaKib);
        return (deltaKib > 4096 ? Ansi.red(bar) : Ansi.green(bar)) + " " + Ansi.dim(label);
    }

    private static void exportCsv(Path path, List<MemoryReport.Snapshot> trajectory) throws IOException {
        var lines = new ArrayList<String>(trajectory.size() + 1);
        lines.add("op,rssKib,nmtCommittedKib");
        for (var s : trajectory) {
            lines.add(s.op() + "," + s.rssKib() + "," + s.nmtCommittedKib());
        }
        Files.write(path, lines);
    }

    private static Path resultsDir() {
        var override = System.getProperty("results.dir");
        var root = (override != null && !override.isBlank()) ? Path.of(override) : Path.of("build", "memory");
        return root.resolve("memory");
    }

    private static String parseMode(String[] args) {
        for (var a : args) {
            if (a.startsWith("--mode=")) {
                return switch (a.substring("--mode=".length()).toLowerCase()) {
                    case "issue628" -> "issue628";
                    case "fixed" -> "fixed";
                    default -> throw new IllegalArgumentException("unknown --mode (use fixed | issue628): " + a);
                };
            }
        }
        return "fixed";
    }

    private static long parseLong(String[] args, String flag, long fallback) {
        for (var a : args) {
            if (a.startsWith(flag)) {
                return Long.parseLong(a.substring(flag.length()));
            }
        }
        return fallback;
    }
}
