package com.pi4j.bench.ffm;

import com.pi4j.bench.common.Ansi;
import com.pi4j.bench.common.EnvManifest;

import org.HdrHistogram.Histogram;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Block E · E5 — Time-to-safepoint with a native call in flight. `critical()` shaves the
/// downcall's thread-state transition — but that transition is exactly what makes a normal
/// downcall *safepoint-safe*: the JVM can reach a global safepoint while a plain downcall
/// blocks, because the thread is parked in native state. A `critical()` call keeps the thread
/// in Java state, so the JVM **cannot** safepoint until the native call returns.
///
/// This runner makes the flip side of the recipe pill measurable. A holder thread loops a
/// deliberately slow blocking `usleep()` — as a plain downcall vs a `critical()` one — while:
///   * a **requester** thread repeatedly forces a global safepoint (`Thread.getAllStackTraces`)
///     and times how long each one takes (the observable time-to-safepoint), and
///   * a **bystander** thread runs innocent Java work, recording the gap between iterations
///     (its p99.9 stall is what a real application would feel).
///
/// With `critical()` and a 10 ms call, both the requester and the bystander stall for ~the call
/// duration; with a plain downcall they sail through. "`critical()` buys nanoseconds on the call
/// and can cost milliseconds JVM-wide if the call is slow." Feeds the guarantees/jitter slide.
public final class SafepointRunner {

    void main(String[] args) throws Throwable {
        var cfg = Config.parse(args);
        var env = EnvManifest.captureSafe();

        System.out.println(Ansi.banner("ffm · E5 · time-to-safepoint under a native call"));
        System.out.printf("  %s %s · measure %ds/variant%n",
                Ansi.dim(Ansi.BULLET), Ansi.dim(env.toString()), cfg.measureSeconds);
        System.out.println("  " + Ansi.dim(Ansi.BULLET
                + " clean absolute TTSP needs a quiesced host (tools/pin-env.sh); the authoritative"));
        System.out.println("  " + Ansi.dim("  record is the -Xlog:safepoint log the runner captures next to this JSON."));

        var plain = plainUsleep();
        var critical = criticalUsleep();

        // Warmup: a throwaway variant absorbs JIT/class-load safepoints so they don't skew the
        // first measured variant (else "plain 1 µs" wrongly shows a startup-sized TTSP spike).
        measure("plain", 1, plain, 1);

        var results = new ArrayList<Result>();
        for (int durationMicros : cfg.callMicros) {
            results.add(measure("plain", durationMicros, plain, cfg.measureSeconds));
            results.add(measure("critical", durationMicros, critical, cfg.measureSeconds));
        }
        for (var r : results) {
            report(r);
        }
        writeJson(cfg, env, results);
    }

    /// Run holder + requester + bystander for the window and collect the two stall tails.
    private static Result measure(String option, int callMicros, MethodHandle usleep, int measureSeconds)
            throws InterruptedException {
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        var bystanderGaps = new Histogram(1L, 60_000_000_000L, 3);
        var ttsp = new Histogram(1L, 60_000_000_000L, 3);

        // Holder: spins the slow blocking call for the whole window.
        var holder = Thread.ofPlatform().name("holder").unstarted(() -> {
            while (running.get()) {
                try {
                    int rc = (int) usleep.invokeExact(callMicros);
                    if (rc != 0) {
                        throw new IllegalStateException("usleep returned " + rc);
                    }
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
        });

        // Requester: forces a global safepoint and times how long it took to be served.
        var requester = Thread.ofPlatform().name("requester").unstarted(() -> {
            while (running.get()) {
                long t0 = System.nanoTime();
                Thread.getAllStackTraces(); // a VM operation → requires a global safepoint
                ttsp.recordValue(Math.max(1L, System.nanoTime() - t0));
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); // ~1 kHz
            }
        });

        // Bystander: innocent Java work; each inter-iteration gap that balloons is a stall it felt.
        var bystander = Thread.ofPlatform().name("bystander").unstarted(() -> {
            long last = System.nanoTime();
            long spin = 0;
            while (running.get()) {
                long now = System.nanoTime();
                bystanderGaps.recordValue(Math.max(1L, now - last));
                last = now;
                for (int i = 0; i < 1_000; i++) {
                    spin += i; // trivial work with safepoint-pollable back-edges
                }
                if (spin == Long.MIN_VALUE) {
                    System.out.print(""); // keep `spin` live
                }
            }
        });

        holder.start();
        bystander.start();
        requester.start();
        Thread.sleep(measureSeconds * 1_000L);
        running.set(false);
        holder.join();
        requester.join();
        bystander.join();

        return new Result(option, callMicros,
                bystanderGaps.getValueAtPercentile(99.9) / 1_000.0,
                bystanderGaps.getMaxValue() / 1_000.0,
                ttsp.getValueAtPercentile(99.9) / 1_000.0,
                ttsp.getMaxValue() / 1_000.0);
    }

    @SuppressWarnings("restricted") // plain blocking downcall — safepoint-safe (transitions to native)
    private static MethodHandle plainUsleep() {
        var linker = Linker.nativeLinker();
        return linker.downcallHandle(lookupUsleep(linker),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    @SuppressWarnings("restricted") // critical() blocking downcall — the safepoint-blocking case
    private static MethodHandle criticalUsleep() {
        var linker = Linker.nativeLinker();
        return linker.downcallHandle(lookupUsleep(linker),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                Linker.Option.critical(false));
    }

    private static java.lang.foreign.MemorySegment lookupUsleep(Linker linker) {
        return linker.defaultLookup().find("usleep").orElseThrow(
                () -> new IllegalStateException("usleep not found in the default lookup"));
    }

    private static void report(Result r) {
        // Descriptive, not a verdict: on an unquiesced host background safepoints (GC/JIT) add
        // noise comparable to the signal, so the honest read is the critical-vs-plain DELTA at
        // each call duration, cross-checked against the -Xlog:safepoint artifact.
        var note = r.option.equals("critical")
                ? Ansi.yellow("critical → no safepoint until the call returns")
                : Ansi.dim("plain → safepoint-safe (parks in native state)");
        System.out.printf("    %s %-9s call=%-6s bystander max=%s  ttsp p99.9=%s max=%s  %s%n",
                Ansi.cyan(Ansi.BULLET), r.option, humanMicros(r.callMicros),
                Ansi.value("%.1f µs".formatted(r.bystanderMaxMicros)),
                Ansi.value("%.1f µs".formatted(r.ttspP999Micros)),
                Ansi.value("%.1f µs".formatted(r.ttspMaxMicros)), note);
    }

    private static String humanMicros(int micros) {
        return micros >= 1000 ? (micros / 1000) + "ms" : micros + "µs";
    }

    private void writeJson(Config cfg, EnvManifest env, List<Result> results) throws Exception {
        var dir = resultsDir();
        Files.createDirectories(dir);
        var rows = results.stream().map(r -> """
                {"option": "%s", "callMicros": %d, "bystanderP999Micros": %.3f, "bystanderMaxMicros": %.3f, "ttspP999Micros": %.3f, "ttspMaxMicros": %.3f}"""
                .formatted(r.option, r.callMicros, r.bystanderP999Micros, r.bystanderMaxMicros,
                        r.ttspP999Micros, r.ttspMaxMicros))
                .toList();
        var json = """
                {
                  "benchmark": "E5-time-to-safepoint",
                  "measureSeconds": %d,
                  "arch": "%s",
                  "jdk": "%s",
                  "cpuModel": "%s",
                  "results": [
                    %s
                  ]
                }
                """.formatted(cfg.measureSeconds, env.arch(), env.jdk(), env.cpuModel(),
                String.join(",\n    ", rows));
        var out = dir.resolve("ffm-safepoint.json");
        Files.writeString(out, json);
        System.out.println("  " + Ansi.ok("results " + Ansi.ARROW + " " + Ansi.cyan(out.toString())));
    }

    private static Path resultsDir() {
        var override = System.getProperty("results.dir");
        var root = (override != null && !override.isBlank()) ? Path.of(override) : Path.of("build", "ffm");
        return root.resolve("ffm");
    }

    private record Result(String option, int callMicros, double bystanderP999Micros,
                          double bystanderMaxMicros, double ttspP999Micros, double ttspMaxMicros) {
    }

    private record Config(int[] callMicros, int measureSeconds) {
        static Config parse(String[] args) {
            int[] micros = {1, 100, 10_000}; // 1 µs / 100 µs / 10 ms
            int measure = 5;
            for (var a : args) {
                if (a.equals("--quick")) {
                    measure = 2;
                } else if (a.startsWith("--call-micros=")) {
                    micros = List.of(value(a).split(",")).stream().mapToInt(Integer::parseInt).toArray();
                } else if (a.startsWith("--measure-seconds=")) {
                    measure = Integer.parseInt(value(a));
                } else if (a.startsWith("--warmup-seconds=")) {
                    continue; // accepted for a uniform lane CLI
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            return new Config(micros, measure);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1);
        }
    }
}
