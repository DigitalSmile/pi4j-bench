package com.pi4j.bench.ffm;

import com.pi4j.Pi4J;
import com.pi4j.bench.common.Ansi;
import com.pi4j.bench.common.BenchProps;
import com.pi4j.bench.common.EnvManifest;
import com.pi4j.context.Context;
import com.pi4j.io.i2c.I2C;
import com.pi4j.io.i2c.I2CConfigBuilder;
import com.pi4j.io.i2c.I2CImplementation;
import com.pi4j.plugin.ffm.providers.i2c.FFMI2CProviderImpl;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/// Block E · E4 — Virtual threads + blocking downcalls: carrier pinning. A blocking FFM downcall
/// runs a native frame, so it **pins** its carrier — Loom cannot unmount a virtual thread parked
/// in native code. `N` tasks run five ways so the report can tell the two stories apart:
///
/// | mode | executor · workload | what it shows |
/// |---|---|---|
/// | `platform-i2c`   | fixed pool · **real Pi4J FFM I2C read** (mock) | v4 hot-call throughput baseline |
/// | `vthread-i2c`    | vthread/task · **real Pi4J FFM I2C read** (mock) | the mergeable v4 datapoint on Loom |
/// | `platform-usleep`| fixed pool · blocking `usleep` | blocking baseline ≈ cores / sleep |
/// | `vthread-pin`    | vthread/task · blocking `usleep` | **collapses** to carrier-count parallelism |
/// | `vthread-yield`  | vthread/task · `Thread.sleep` | scales: the JDK sleep unmounts the vthread |
///
/// The `-i2c` pair is the real Pi4J measurement (a short non-blocking ioctl to the mock); the
/// `usleep` triplet isolates the pinning mechanism — `vthread-pin` matching `platform-usleep`
/// (not `vthread-yield`) is the whole point: a *blocking* native call gives virtual threads no
/// scalability benefit. Capture `jdk.VirtualThreadPinned` JFR events (`--jfr`) to see the pin.
public final class VThreadPinningRunner {

    private enum Executor { PLATFORM, VIRTUAL }

    private enum Workload { I2C, USLEEP, YIELD }

    private record Mode(String name, Executor executor, Workload workload) {
    }

    private static final List<Mode> MODES = List.of(
            new Mode("platform-i2c", Executor.PLATFORM, Workload.I2C),
            new Mode("vthread-i2c", Executor.VIRTUAL, Workload.I2C),
            new Mode("platform-usleep", Executor.PLATFORM, Workload.USLEEP),
            new Mode("vthread-pin", Executor.VIRTUAL, Workload.USLEEP),
            new Mode("vthread-yield", Executor.VIRTUAL, Workload.YIELD));

    void main(String[] args) throws Throwable {
        var cfg = Config.parse(args);
        var env = EnvManifest.captureSafe();
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println(Ansi.banner("ffm · E4 · virtual threads + blocking downcalls"));
        System.out.printf("  %s %s · %s cores · usleep(%d µs) · %d calls/task%n",
                Ansi.dim(Ansi.BULLET), Ansi.dim(env.toString()), Ansi.value(String.valueOf(cores)),
                cfg.sleepMicros, cfg.callsPerTask);

        var usleep = usleepHandle();
        var pi4j = Pi4J.newContextBuilder().add(new FFMI2CProviderImpl()).build();
        var i2c = pi4j.create(I2CConfigBuilder.newInstance()
                .bus(BenchProps.intProp("bench.i2c.bus", 99))
                .device(BenchProps.hexProp("bench.i2c.device", 0x1C))
                .i2cImplementation(I2CImplementation.DIRECT));
        // Prime the register so every read has a defined value (write once, single-threaded).
        int register = BenchProps.hexProp("bench.i2c.register", 0xFF);
        i2c.writeRegister(register, new byte[] {0x01});

        var results = new ArrayList<Result>();
        try {
            for (int n : cfg.taskCounts) {
                for (var mode : MODES) {
                    var hist = new ConcurrentHistogram(1L, 60_000_000_000L, 3);
                    long elapsedNanos = runMode(mode, n, cfg.callsPerTask, cfg.sleepMicros, cores,
                            usleep, i2c, register, hist);
                    double ops = (double) n * cfg.callsPerTask;
                    var r = new Result(n, mode.name(), ops / (elapsedNanos / 1e9),
                            hist.getValueAtPercentile(50) / 1_000.0,
                            hist.getValueAtPercentile(99) / 1_000.0,
                            hist.getMaxValue() / 1_000.0);
                    results.add(r);
                    report(r);
                }
            }
        } finally {
            pi4j.shutdown();
        }
        writeJson(cfg, env, cores, results);
    }

    /// Submit `n` tasks that all await a start latch (so virtual threads mount together), each
    /// performing `callsPerTask` operations of the mode's workload, then release and time it.
    private static long runMode(Mode mode, int n, int callsPerTask, int sleepMicros, int cores,
                                MethodHandle usleep, I2C i2c, int register, Histogram hist) throws Exception {
        ExecutorService exec = mode.executor() == Executor.PLATFORM
                ? Executors.newFixedThreadPool(cores)
                : Executors.newVirtualThreadPerTaskExecutor();
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<Void>>(n);
        for (int t = 0; t < n; t++) {
            tasks.add(() -> {
                var buf = new byte[1]; // per-task read buffer — no cross-thread sharing
                start.await();
                for (int c = 0; c < callsPerTask; c++) {
                    long t0 = System.nanoTime();
                    switch (mode.workload()) {
                        case I2C -> i2c.readRegister(register, buf); // real Pi4J FFM downcall to the mock
                        case USLEEP -> blockingUsleep(usleep, sleepMicros); // blocking native frame — pins
                        case YIELD -> Thread.sleep(Duration.ofNanos(sleepMicros * 1_000L)); // JDK sleep — unmounts
                    }
                    hist.recordValue(Math.max(1L, System.nanoTime() - t0));
                }
                return null;
            });
        }
        try (exec) {
            var futures = new ArrayList<Future<Void>>(n);
            for (var task : tasks) {
                futures.add(exec.submit(task));
            }
            long begin = System.nanoTime();
            start.countDown();
            for (var f : futures) {
                f.get();
            }
            return System.nanoTime() - begin;
        }
    }

    /// Invoke the blocking `usleep` downcall; `invokeExact` throws `Throwable`, so funnel it into a
    /// `RuntimeException` the `Callable` can propagate.
    private static void blockingUsleep(MethodHandle usleep, int micros) {
        try {
            int rc = (int) usleep.invokeExact(micros);
            if (rc != 0) {
                throw new IllegalStateException("usleep returned " + rc);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @SuppressWarnings("restricted") // libc usleep downcall — the blocking native frame we study
    private static MethodHandle usleepHandle() {
        var linker = Linker.nativeLinker();
        return linker.downcallHandle(
                linker.defaultLookup().find("usleep").orElseThrow(
                        () -> new IllegalStateException("usleep not found in the default lookup")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private static void report(Result r) {
        var tag = switch (r.mode) {
            case "vthread-yield" -> Ansi.green("scales");
            case "vthread-pin" -> Ansi.yellow("pinned → carrier-bound");
            case "vthread-i2c", "platform-i2c" -> Ansi.cyan("pi4j-ffm");
            default -> Ansi.dim("baseline");
        };
        System.out.printf("    %s N=%-6d %-16s %s ops/s  %s p99=%.1f µs%n",
                Ansi.cyan(Ansi.BULLET), r.tasks, r.mode,
                Ansi.value("%,.0f".formatted(r.throughputPerSec)), tag, r.p99Micros);
    }

    private void writeJson(Config cfg, EnvManifest env, int cores, List<Result> results) throws Exception {
        var dir = resultsDir();
        Files.createDirectories(dir);
        var rows = results.stream().map(r -> """
                {"tasks": %d, "mode": "%s", "throughputPerSec": %.3f, "p50Micros": %.3f, "p99Micros": %.3f, "maxMicros": %.3f}"""
                .formatted(r.tasks, r.mode, r.throughputPerSec, r.p50Micros, r.p99Micros, r.maxMicros))
                .toList();
        var json = """
                {
                  "benchmark": "E4-vthread-pinning",
                  "cores": %d,
                  "sleepMicros": %d,
                  "callsPerTask": %d,
                  "arch": "%s",
                  "jdk": "%s",
                  "cpuModel": "%s",
                  "results": [
                    %s
                  ]
                }
                """.formatted(cores, cfg.sleepMicros, cfg.callsPerTask, env.arch(), env.jdk(),
                env.cpuModel(), String.join(",\n    ", rows));
        var out = dir.resolve("ffm-vthreads.json");
        Files.writeString(out, json);
        System.out.println("  " + Ansi.ok("results " + Ansi.ARROW + " " + Ansi.cyan(out.toString())));
    }

    private static Path resultsDir() {
        var override = System.getProperty("results.dir");
        var root = (override != null && !override.isBlank()) ? Path.of(override) : Path.of("build", "ffm");
        return root.resolve("ffm");
    }

    private record Result(int tasks, String mode, double throughputPerSec,
                          double p50Micros, double p99Micros, double maxMicros) {
    }

    private record Config(int[] taskCounts, int callsPerTask, int sleepMicros) {
        static Config parse(String[] args) {
            int[] counts = {100, 1_000, 10_000};
            int calls = 4;
            int sleep = 200;
            boolean quick = false;
            for (var a : args) {
                if (a.equals("--quick")) {
                    quick = true;
                } else if (a.startsWith("--tasks=")) {
                    counts = List.of(value(a).split(",")).stream().mapToInt(Integer::parseInt).toArray();
                } else if (a.startsWith("--calls-per-task=")) {
                    calls = Integer.parseInt(value(a));
                } else if (a.startsWith("--sleep-micros=")) {
                    sleep = Integer.parseInt(value(a));
                } else if (a.startsWith("--warmup-seconds=") || a.startsWith("--measure-seconds=")) {
                    continue; // accepted for a uniform lane CLI; this runner is task-bounded
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            if (quick) {
                counts = new int[] {100, 1_000};
                calls = 2;
            }
            return new Config(counts, calls, sleep);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1);
        }
    }
}
