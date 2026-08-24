package com.pi4j.bench.warmup;

import com.pi4j.bench.common.Ansi;
import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.HdrRecorder;
import com.pi4j.bench.common.WarmupCurve;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/// FFM warmup microbenchmark — measures how long a `java.lang.foreign` downcall takes to
/// reach steady state from a cold JVM. Each iteration invokes a trivial libc function
/// (`getpid`) through a shared downcall handle and records the wall-clock cost; the first
/// calls pay interpreter + C2-compile costs, later ones settle to the JIT-compiled stub.
///
/// This is the metric an AOT cache (JDK 25 / JEP 483) or Project Leyden should improve:
/// with the downcall stub + method handle graph pre-linked, the early-iteration tax shrinks.
/// The orchestrator runs three variants of this same program — baseline JIT, `-XX:AOTCache`,
/// and a Leyden EA JDK — labelling each via `--label`, so their curves overlay on one chart.
///
/// Pure FFM + bench-common: no Pi4J, no kernel mocks, so it runs on any JDK 25 host.
public final class WarmupRunner {

    @SuppressWarnings("restricted") // libc getpid downcall — the whole point of the bench
    void main(String[] args) throws Throwable {
        var cfg = Config.parse(args);
        var env = EnvManifest.captureSafe();
        System.out.println(Ansi.banner("warmup · FFM downcall · " + cfg.label));
        System.out.println("  " + Ansi.dim(Ansi.BULLET) + " " + Ansi.dim(env.toString()));
        System.out.printf("  %s %s iterations · getpid() via FFM · %s%n",
                Ansi.dim(Ansi.BULLET), Ansi.value("%,d".formatted(cfg.iterations)),
                aotLabel());

        // One shared downcall handle to libc getpid() — no args, returns int. Cheap enough
        // that the measured cost is dominated by the FFM invoke path, which is the point.
        var linker = Linker.nativeLinker();
        var getpid = linker.downcallHandle(
                linker.defaultLookup().find("getpid").orElseThrow(
                        () -> new IllegalStateException("getpid not found in the native linker default lookup")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));

        // Cap the ordered-sample buffer; the warmup shape lives in the early iterations.
        var curve = new WarmupCurve((int) Math.min(cfg.iterations, 2_000_000L));
        var hdr = new HdrRecorder();
        long firstNanos = measure(getpid, curve, hdr, cfg.iterations);

        var samples = curve.toArray();
        var steady = steadyState(samples);
        report(cfg, env, firstNanos, samples, steady, hdr);
    }

    /// The tight measured loop. `acc` keeps the result live so the invoke can't be elided;
    /// returned so the JIT can't prove it dead.
    private static long measure(MethodHandle getpid, WarmupCurve curve, HdrRecorder hdr, long iterations)
            throws Throwable {
        long acc = 0;
        long first = 0;
        for (long i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            acc += (int) getpid.invokeExact();
            long d = System.nanoTime() - t0;
            if (i == 0) first = d;
            curve.record(d);
            hdr.recordNanos(d);
        }
        // Consume acc so the whole loop can't be optimised away.
        if (acc == Long.MIN_VALUE) {
            System.out.print("");
        }
        return first;
    }

    /// Steady-state = the first iteration after which a trailing moving average stays within
    /// 25% of the settled cost (the median of the final 10% of samples) for the rest of the
    /// run. Returns the iteration index and the settled median (ns).
    private static Steady steadyState(long[] samples) {
        if (samples.length < 100) {
            return new Steady(0, samples.length == 0 ? 0 : median(samples, 0, samples.length));
        }
        long settled = median(samples, (int) (samples.length * 0.9), samples.length);
        double threshold = settled * 1.25;
        int window = Math.max(50, samples.length / 200);
        long sum = 0;
        for (int i = 0; i < window; i++) {
            sum += samples[i];
        }
        // Slide the window; the first index where the average is under threshold AND never
        // exceeds it again (checked greedily) is our steady-state onset.
        for (int i = window; i < samples.length; i++) {
            double avg = (double) sum / window;
            if (avg <= threshold) {
                return new Steady(i - window, settled);
            }
            sum += samples[i] - samples[i - window];
        }
        return new Steady(samples.length - window, settled);
    }

    private static long median(long[] a, int from, int to) {
        var slice = Arrays.copyOfRange(a, from, to);
        Arrays.sort(slice);
        return slice[slice.length / 2];
    }

    private void report(Config cfg, EnvManifest env, long firstNanos, long[] samples,
                        Steady steady, HdrRecorder hdr) throws IOException {
        var pct = hdr.percentiles();
        // Cumulative wall-clock to reach steady state — the headline "warmup time".
        long toSteadyNanos = 0;
        for (int i = 0; i < steady.iter() && i < samples.length; i++) {
            toSteadyNanos += samples[i];
        }

        System.out.printf("%n  %s  %s%n", Ansi.okText(Ansi.CHECK + " warmup"), Ansi.dim(cfg.label));
        System.out.printf("    %s %s%n", Ansi.cyan(pad("first call")),
                Ansi.yellow("%,.3f µs".formatted(firstNanos / 1_000.0)));
        System.out.printf("    %s %s%n", Ansi.cyan(pad("steady @")),
                Ansi.value("iter %,d".formatted(steady.iter())
                        + " (%.1f ms in)".formatted(toSteadyNanos / 1_000_000.0)));
        System.out.printf("    %s %s%n", Ansi.cyan(pad("steady cost")),
                Ansi.value("%,.3f µs".formatted(steady.settledNanos() / 1_000.0)));
        System.out.printf("    %s %s%n", Ansi.cyan(pad("p50 / p99")),
                Ansi.value("%,.3f / %,.3f µs".formatted(pct.p50Micros(), pct.p99Micros())));

        var dir = resultsDir();
        Files.createDirectories(dir);
        var base = "warmup-v4-" + cfg.label;
        new WarmupReport(cfg.label, cfg.iterations, firstNanos, steady.iter(), toSteadyNanos,
                steady.settledNanos(), env, pct).writeJson(dir.resolve(base + ".json"));
        // Reuse WarmupCurve's CSV writer via a fresh curve isn't needed — write directly.
        writeCurveCsv(dir.resolve(base + "-curve.csv"), samples);
        System.out.println("  " + Ansi.ok("results " + Ansi.ARROW + " "
                + Ansi.cyan(dir.resolve(base + ".{json,-curve.csv}").toString())));
    }

    private static void writeCurveCsv(Path path, long[] samples) throws IOException {
        // Downsample to ~5k rows so the CSV stays plottable for very long runs.
        int stride = Math.max(1, samples.length / 5_000);
        var lines = new java.util.ArrayList<String>(samples.length / stride + 1);
        lines.add("iteration,latencyNanos");
        for (int i = 0; i < samples.length; i += stride) {
            lines.add(i + "," + samples[i]);
        }
        Files.write(path, lines);
    }

    private static String aotLabel() {
        var aot = System.getProperty("jdk.aot.cache", System.getenv("AOT_CACHE"));
        return aot != null ? Ansi.yellow("[AOT cache]") : Ansi.dim("[JIT baseline]");
    }

    private static String pad(String s) {
        return Ansi.cyan(s.length() >= 12 ? s : s + " ".repeat(12 - s.length()));
    }

    private static Path resultsDir() {
        var override = System.getProperty("results.dir");
        var root = (override != null && !override.isBlank()) ? Path.of(override) : Path.of("build", "warmup");
        return root.resolve("warmup");
    }

    private record Steady(int iter, long settledNanos) {
    }

    record Config(long iterations, int curveSamples, String label) {
        static Config parse(String[] args) {
            long iterations = 200_000;
            String label = "jit";
            for (var a : args) {
                if (a.startsWith("--iterations=")) {
                    iterations = Long.parseLong(value(a));
                } else if (a.startsWith("--label=")) {
                    label = value(a);
                } else if (a.startsWith("--warmup-seconds=") || a.startsWith("--measure-seconds=")) {
                    // Accepted for a uniform lane CLI; this microbench is iteration-bounded.
                    continue;
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            return new Config(iterations, 10_000, label);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1);
        }
    }
}
