package com.pi4j.bench.latency;

import com.pi4j.bench.common.Ansi;
import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.GcPressure;
import com.pi4j.bench.common.GpioMockStimulus;
import com.pi4j.bench.common.HdrRecorder;
import com.pi4j.bench.common.WarmupCurve;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/// Block C — edge event → listener latency/jitter runner (custom, NOT JMH).
///
/// A single process drives both ends: a stimulus thread flips a mock GPIO input line
/// through debugfs (raising a kernel edge IRQ), and the Pi4J listener wakes on the
/// far side of `poll()`. We publish `t0` immediately before the edge-raising write
/// and read `t1` in the listener callback, so `t1 - t0` is the full
/// edge→poll→dispatch→listener path — measured identically for V3 (gpiod) and V4
/// (ffm), because the public `DigitalStateChangeEvent` carries no kernel timestamp
/// on either lane.
///
/// The flavor is fixed at build time (`-Pv=3|4` selects `src/v${v}/java`'s [Lanes]).
/// GC (G1 vs ZGC) and native-image are JVM-launch choices, so they're read back from
/// the running VM for the result label rather than toggled here; `--gc-pressure`
/// spins up a background allocator (plan §6.3 config matrix).
public final class LatencyRunner {

    void main(String[] args) throws Exception {
        var cfg = Config.parse(args);
        var env = EnvManifest.captureSafe();
        var gc = detectGc();
        var lane0 = Lanes.create();
        System.out.println(Ansi.banner("latency · " + lane0.name() + " / " + gc));
        System.out.println("  " + Ansi.dim(Ansi.BULLET) + " " + Ansi.dim(env.toString()));
        System.out.printf("  %s edge%slistener round-trip · %s Hz · warmup %ss · measure %ss%s%n",
                Ansi.dim(Ansi.BULLET), Ansi.ARROW, Ansi.value(String.valueOf(cfg.rateHz())),
                Ansi.value(String.valueOf(cfg.warmupSeconds())), Ansi.value(String.valueOf(cfg.measureSeconds())),
                cfg.gcPressure() ? "  " + Ansi.yellow("[gc-pressure " + cfg.gcPressureMbps() + " MB/s]") : "");

        var lane = lane0;
        if (!GpioMockStimulus.available(lane.debugfsLabel(), lane.lineOffset())) {
            System.err.printf("""
                    FATAL: mock GPIO stimulus node not writable:
                      %s
                    Block C needs the gpio mock loaded and its debugfs writable by this JVM.
                    Load it (loadMocks / gpio-setup.sh) and run under tools/pin-env.sh (root),
                    or grant the node: sudo chmod -R a+rw /sys/kernel/debug/gpio-mock
                    %n""", GpioMockStimulus.nodeFor(lane.debugfsLabel(), lane.lineOffset()));
            System.exit(2);
        }

        var hdr = new HdrRecorder();
        var writeCost = new HdrRecorder();  // stimulus-write offset, sampled every iteration
        var curve = new WarmupCurve(cfg.warmupCurveSamples());
        try (lane) {
            lane.open();
            var result = measure(lane, cfg, hdr, writeCost, curve);
            report(lane.name(), cfg, gc, env, hdr, writeCost, curve, result);
        }
    }

    /// Ping-pong loop: publish `t0`, raise the edge, wait for the listener's `t1`.
    /// Exactly one edge is in flight at a time, so each write yields one callback
    /// unless a GC pause overruns the timeout (counted as a missed sample).
    private RunResult measure(Lane lane, Config cfg, HdrRecorder hdr, HdrRecorder writeCost,
                              WarmupCurve curve) throws Exception {
        // One-slot rendezvous of the per-edge delta. Capacity 1 (not a SynchronousQueue)
        // so the listener's offer never races the driver's poll into a false miss; the
        // driver clear()s at the top of each sample to drop any late-arriving straggler.
        var handoff = new ArrayBlockingQueue<Long>(1);
        var pendingT0 = new AtomicLong();
        lane.onEdge(() -> {
            long t1 = System.nanoTime();
            handoff.offer(t1 - pendingT0.get());
        });

        long periodNanos = 1_000_000_000L / cfg.rateHz();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(200);
        long now = System.nanoTime();
        long warmupEnd = now + TimeUnit.SECONDS.toNanos(cfg.warmupSeconds());
        long measureEnd = warmupEnd + TimeUnit.SECONDS.toNanos(cfg.measureSeconds());

        long missed = 0;
        boolean level = false;
        boolean measuring = false;
        GcPressure pressure = null;

        // Live progress on a daemon thread — it only READS these atomics, so the tight
        // sample loop never blocks on console I/O (which would spike an individual t1−t0).
        var recorded = new AtomicLong();
        var missedV = new AtomicLong();
        var phase = new AtomicInteger(0); // 0 = warmup, 1 = measure
        System.out.println("  " + Ansi.yellow(Ansi.GEAR + " warming up " + cfg.warmupSeconds() + "s "
                + Ansi.dim("(JIT ramp; samples discarded)")));
        var progress = startProgress(cfg, phase, recorded, missedV);
        progress.start();
        try (var stimulus = GpioMockStimulus.open(lane.debugfsLabel(), lane.lineOffset())) {
            long deadline = now;
            while ((now = System.nanoTime()) < measureEnd) {
                if (!measuring && now >= warmupEnd) {
                    measuring = true;
                    phase.set(1);
                    System.out.println("  " + Ansi.cyan(Ansi.GEAR + " measuring " + cfg.measureSeconds() + "s "
                            + Ansi.dim("(recording latency)")));
                    if (cfg.gcPressure()) {
                        pressure = GcPressure.start(cfg.gcPressureMbps());
                    }
                }
                handoff.clear();            // drop a straggler from a prior timed-out sample
                level = !level;
                long t0 = System.nanoTime();
                pendingT0.set(t0);          // publish before the edge-raising write
                stimulus.write(level);      // edge fires synchronously inside write()
                long tAfterWrite = System.nanoTime();
                if (measuring) {
                    // The true edge fires *inside* write(), so [t0, tAfterWrite] bounds the
                    // stimulus overhead baked into every t1−t0 sample — report it beside the
                    // latency so the mock's absolute offset is visible, not hidden.
                    writeCost.recordNanos(tAfterWrite - t0);
                }
                Long delta = handoff.poll(timeoutNanos, TimeUnit.NANOSECONDS);
                if (delta == null || delta < 0) {
                    missed++;               // GC overran the timeout, or a stale straggler
                    if (measuring) missedV.incrementAndGet();
                } else {
                    curve.record(delta);                 // first N raw samples (warmup shape)
                    if (measuring) {
                        hdr.recordNanos(delta);
                        recorded.incrementAndGet();
                    }
                }
                deadline += periodNanos;
                parkUntil(deadline);
            }
        } finally {
            progress.interrupt();
            if (pressure != null) {
                pressure.close();
            }
        }
        return new RunResult(missed);
    }

    private static void parkUntil(long deadlineNanos) {
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    /// A daemon that prints a phase/elapsed/sample heartbeat every 5 s. Reads the shared
    /// atomics only — it never touches the (non-thread-safe) histogram — so it can't perturb
    /// the measured path.
    private static Thread startProgress(Config cfg, AtomicInteger phase, AtomicLong recorded, AtomicLong missed) {
        long start = System.nanoTime();
        var t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(5_000);
                    long elapsed = (System.nanoTime() - start) / 1_000_000_000L;
                    boolean measuring = phase.get() == 1;
                    long limit = measuring ? cfg.measureSeconds() : cfg.warmupSeconds();
                    long inPhase = measuring ? Math.max(0, elapsed - cfg.warmupSeconds()) : elapsed;
                    long m = missed.get();
                    System.out.printf("    %s %s  %s  samples=%s missed=%s%n",
                            Ansi.dim(Ansi.GEAR),
                            measuring ? Ansi.cyan("measure") : Ansi.yellow("warmup "),
                            Ansi.dim(Math.min(inPhase, limit) + "s/" + limit + "s"),
                            Ansi.value("%,d".formatted(recorded.get())),
                            m == 0 ? Ansi.green("0") : Ansi.yellow(Long.toString(m)));
                }
            } catch (InterruptedException ignored) {
                // clean stop at end of measurement
            }
        }, "latency-progress");
        t.setDaemon(true);
        return t;
    }

    private void report(String lane, Config cfg, String gc, EnvManifest env,
                        HdrRecorder hdr, HdrRecorder writeCost, WarmupCurve curve,
                        RunResult result) throws IOException {
        var pct = hdr.percentiles();
        var writePct = writeCost.percentiles();
        long total = pct.count() + result.missed();
        double missRate = total == 0 ? 0 : 100.0 * result.missed() / total;

        System.out.printf("%n  %s  %s%n", Ansi.okText(Ansi.CHECK + " latency"),
                Ansi.dim(lane + " / " + gc + (cfg.gcPressure() ? " / gc-pressure" : "")));
        // Full percentile ladder — the tail is the story for edge→listener jitter.
        row("p50", pct.p50Micros(), false);
        row("p90", pct.p90Micros(), false);
        row("p99", pct.p99Micros(), false);
        row("p99.9", pct.p999Micros(), false);
        row("p99.99", pct.p9999Micros(), false);
        row("max", pct.maxMicros(), true);
        row("mean", pct.meanMicros(), false);
        System.out.printf("  %s samples=%s  missed=%s %s%n", Ansi.dim(Ansi.BULLET),
                Ansi.value("%,d".formatted(pct.count())),
                result.missed() == 0 ? Ansi.green("0") : Ansi.yellow(Long.toString(result.missed())),
                Ansi.dim("(%.3f%% of %,d)".formatted(missRate, total)));
        System.out.printf("  %s stimulus write offset %s p50=%s p99=%s%n", Ansi.dim(Ansi.BULLET),
                Ansi.dim("(in-memory debugfs, common to both lanes)"),
                Ansi.dim("%.3fµs".formatted(writePct.p50Micros())),
                Ansi.dim("%.3fµs".formatted(writePct.p99Micros())));

        var dir = resultsDir(lane);
        Files.createDirectories(dir);
        var base = lane + "-" + gc.toLowerCase() + (cfg.gcPressure() ? "-gcpressure" : "");
        hdr.exportHgrm(dir.resolve(base + ".hgrm"));
        writeCost.exportHgrm(dir.resolve(base + "-writecost.hgrm"));
        curve.exportCsv(dir.resolve(base + "-warmup.csv"));
        new LatencyReport(lane, gc, cfg.gcPressure(), cfg.gcPressureMbps(),
                cfg.rateHz(), cfg.warmupSeconds(), cfg.measureSeconds(),
                result.missed(), env, pct, writePct)
                .writeJson(dir.resolve(base + ".json"));
        System.out.println("  " + Ansi.ok("results " + Ansi.ARROW + " "
                + Ansi.cyan(dir.resolve(base + ".{json,hgrm,-warmup.csv}").toString())));
    }

    /// One aligned percentile row: `  label   value µs`, value bold (yellow for the max tail).
    private static void row(String label, double micros, boolean tail) {
        var v = "%9.3f µs".formatted(micros);
        System.out.printf("    %s %s%n", Ansi.cyan(padRight(label, 7)),
                tail ? Ansi.yellow(v) : Ansi.value(v));
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static Path resultsDir(String lane) {
        var override = System.getProperty("results.dir");
        var root = (override != null && !override.isBlank())
                ? Path.of(override)
                : Path.of("build", "latency");
        return root.resolve("latency").resolve(lane);
    }

    /// Best-effort label from the running collector — G1 / ZGC / Serial / Parallel /
    /// Shenandoah — so the result records the GC actually in effect, not a flag.
    private static String detectGc() {
        // A native image has no JIT and (by default) Serial GC without the JIT-style GC
        // MXBeans; label the lane "native" so its flat-warmup result is distinguishable.
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return "native";
        }
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            var n = bean.getName();
            if (n.contains("G1")) return "G1";
            if (n.contains("ZGC") || n.contains("ZDriver") || n.startsWith("Z")) return "ZGC";
            if (n.contains("Shenandoah")) return "Shenandoah";
            if (n.contains("PS") || n.contains("Parallel")) return "Parallel";
            if (n.contains("Copy") || n.contains("MarkSweep") || n.contains("Serial")) return "Serial";
        }
        return "unknown";
    }

    private record RunResult(long missed) {
    }

    record Config(int rateHz, int warmupSeconds, int measureSeconds,
                  int warmupCurveSamples, boolean gcPressure, int gcPressureMbps) {
        static Config parse(String[] args) {
            int rateHz = 200;
            int warmup = 30;
            int measure = 60;
            int curve = 10_000;
            boolean gcPressure = false;
            int mbps = 200;
            for (var a : args) {
                switch (a) {
                    case "--gc-pressure" -> gcPressure = true;
                    default -> {
                        if (a.startsWith("--rate-hz=")) {
                            rateHz = Integer.parseInt(value(a));
                        } else if (a.startsWith("--warmup-seconds=")) {
                            warmup = Integer.parseInt(value(a));
                        } else if (a.startsWith("--measure-seconds=")) {
                            measure = Integer.parseInt(value(a));
                        } else if (a.startsWith("--warmup-curve=")) {
                            curve = Integer.parseInt(value(a));
                        } else if (a.startsWith("--gc-pressure-mbps=")) {
                            mbps = Integer.parseInt(value(a));
                        } else {
                            throw new IllegalArgumentException("unknown arg: " + a);
                        }
                    }
                }
            }
            return new Config(rateHz, warmup, measure, curve, gcPressure, mbps);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1);
        }
    }
}
