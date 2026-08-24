package com.pi4j.bench.ffm;

import com.pi4j.Pi4J;
import com.pi4j.bench.common.Ansi;
import com.pi4j.bench.common.BenchProps;
import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.RssSampler;
import com.pi4j.context.Context;
import com.pi4j.io.i2c.I2C;
import com.pi4j.io.i2c.I2CConfigBuilder;
import com.pi4j.io.i2c.I2CImplementation;
import com.pi4j.plugin.ffm.providers.i2c.FFMI2CProviderImpl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/// Block E · E6 — Cleaner pressure from `Arena.ofAuto()`. Issue #628 showed an RSS ramp; this
/// quantifies *why* GC doesn't keep up. Short-lived native segments are allocated at a fixed
/// rate two ways:
///
///   * `ofAuto`   — one shared [Arena#ofAuto]; each segment's memory is freed only after it
///     becomes unreachable, **then** a GC cycle finds it, **then** the Cleaner thread runs.
///   * `ofConfined` — a per-call [Arena#ofConfined] closed immediately, so the free is
///     deterministic and synchronous (the fix's shape).
///
/// The runner paces allocations to `--rate` per second for `--measure-seconds`, samples RSS the
/// whole time, then STOPS and keeps sampling a drain window to measure **reclamation lag** — the
/// seconds from "load stops" to "RSS actually falls". GC pause count/time (via the GC MX beans)
/// bracket each phase. `ofAuto` peaks higher and drains slowly (unreachable → GC → Cleaner),
/// while `ofConfined` stays flat — turning #628 from "a bug we fixed" into "a mechanism you now
/// understand." Strengthens the memory-rules slide.
///
/// Each iteration also performs a **real Pi4J FFM I2C read** against the shared mock, so every
/// loop genuinely crosses the FFM boundary (a v4 hot call) — the arena strategy is the only
/// variable between the two modes, isolating the Cleaner's reclamation lag that #628 exhibited.
public final class CleanerPressureRunner {

    private static final int SEGMENT_BYTES = 4 * 1024; // an ioctl-buffer-sized short-lived segment

    void main(String[] args) throws Throwable {
        var cfg = Config.parse(args);
        var env = EnvManifest.captureSafe();

        System.out.println(Ansi.banner("ffm · E6 · Cleaner pressure from Arena.ofAuto()"));
        System.out.printf("  %s %s · %,d seg/s × %ds · %d B/segment · Pi4J FFM I2C read/iter%n",
                Ansi.dim(Ansi.BULLET), Ansi.dim(env.toString()), cfg.ratePerSecond,
                cfg.measureSeconds, SEGMENT_BYTES);

        var pi4j = Pi4J.newContextBuilder().add(new FFMI2CProviderImpl()).build();
        var i2c = pi4j.create(I2CConfigBuilder.newInstance()
                .bus(BenchProps.intProp("bench.i2c.bus", 99))
                .device(BenchProps.hexProp("bench.i2c.device", 0x1C))
                .i2cImplementation(I2CImplementation.DIRECT));
        int register = BenchProps.hexProp("bench.i2c.register", 0xFF);
        i2c.writeRegister(register, new byte[] {0x01});

        var results = new ArrayList<Result>();
        try {
            for (var mode : List.of("ofConfined", "ofAuto")) {
                results.add(run(mode, cfg, i2c, register));
            }
        } finally {
            pi4j.shutdown();
        }
        for (var r : results) {
            report(r);
        }
        writeJson(cfg, env, results);
    }

    private static Result run(String mode, Config cfg, I2C i2c, int register) throws InterruptedException {
        boolean auto = mode.equals("ofAuto");
        Arena autoArena = auto ? Arena.ofAuto() : null;
        var readBuf = new byte[1];

        long baselineRss = settleAndReadRss();
        long gc0Count = gcCount();
        long gc0Millis = gcMillis();

        var trajectory = new ArrayList<Sample>();
        long start = System.nanoTime();
        long nanosPerAlloc = 1_000_000_000L / Math.max(1, cfg.ratePerSecond);
        long nextSampleAt = start;
        long next = start;
        long allocations = 0;
        long peakRss = baselineRss;

        long measureNanos = cfg.measureSeconds * 1_000_000_000L;
        while (System.nanoTime() - start < measureNanos) {
            // A real Pi4J FFM I2C read every iteration — the loop genuinely crosses the boundary.
            i2c.readRegister(register, readBuf);
            // Allocate one short-lived native buffer; drop the reference immediately. This is the
            // ONLY difference between the two modes — the #628 mechanism, isolated.
            if (auto) {
                MemorySegment seg = autoArena.allocate(SEGMENT_BYTES);
                touch(seg); // fault the pages so RSS reflects the allocation
            } else {
                try (var a = Arena.ofConfined()) {
                    touch(a.allocate(SEGMENT_BYTES));
                } // freed here, deterministically
            }
            allocations++;

            long now = System.nanoTime();
            if (now >= nextSampleAt) {
                long rss = RssSampler.vmRssKib();
                peakRss = Math.max(peakRss, rss);
                trajectory.add(new Sample((now - start) / 1_000_000, rss));
                nextSampleAt += 100_000_000L; // ~10 Hz
            }
            next += nanosPerAlloc;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                next = System.nanoTime(); // fell behind — reset the schedule, don't overshoot
            }
        }

        long loadStopMillis = (System.nanoTime() - start) / 1_000_000;
        long gcDuringCount = gcCount() - gc0Count;
        long gcDuringMillis = gcMillis() - gc0Millis;

        // Drain window: load has stopped. Keep sampling until RSS falls back near baseline or the
        // drain window elapses — the first time it returns is the reclamation lag.
        long reclaimLagMillis = -1;
        long drainStart = System.nanoTime();
        long drainBudget = cfg.drainSeconds * 1_000_000_000L;
        long nearBaseline = baselineRss + (peakRss - baselineRss) / 5; // within 20% of the ramp
        while (System.nanoTime() - drainStart < drainBudget) {
            long rss = RssSampler.vmRssKib();
            long tMillis = loadStopMillis + (System.nanoTime() - drainStart) / 1_000_000;
            trajectory.add(new Sample(tMillis, rss));
            if (reclaimLagMillis < 0 && rss <= nearBaseline) {
                reclaimLagMillis = (System.nanoTime() - drainStart) / 1_000_000;
            }
            LockSupport.parkNanos(100_000_000L);
        }

        // A forced GC + settle shows where memory lands once the Cleaner has definitely run.
        System.gc();
        long afterGcRss = settleAndReadRss();

        return new Result(mode, cfg.ratePerSecond, allocations, baselineRss, peakRss, afterGcRss,
                reclaimLagMillis, gcDuringCount, gcDuringMillis, trajectory);
    }

    /// Read a value from the segment so the OS actually backs the pages (RSS, not just committed).
    private static void touch(MemorySegment seg) {
        seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 1);
        seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, seg.byteSize() - 1, (byte) 1);
    }

    private static long settleAndReadRss() throws InterruptedException {
        System.gc();
        Thread.sleep(200);
        return RssSampler.vmRssKib();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }

    private static void report(Result r) {
        long rampKib = r.peakRssKib - r.baselineRssKib;
        var tag = r.mode.equals("ofAuto")
                ? Ansi.yellow("ramp " + rampKib + " KiB · lag "
                        + (r.reclaimLagMillis < 0 ? ">drain" : r.reclaimLagMillis + " ms"))
                : Ansi.green("flat (Δ " + rampKib + " KiB)");
        System.out.printf("    %s %-11s peak=%,d KiB after-gc=%,d KiB  gc=%d/%dms  %s%n",
                Ansi.cyan(Ansi.BULLET), r.mode, r.peakRssKib, r.afterGcRssKib,
                r.gcCount, r.gcMillis, tag);
    }

    private void writeJson(Config cfg, EnvManifest env, List<Result> results) throws Exception {
        var dir = resultsDir();
        Files.createDirectories(dir);
        var rows = results.stream().map(r -> {
            var traj = r.trajectory.stream()
                    .map(s -> "{\"tMillis\": %d, \"rssKib\": %d}".formatted(s.tMillis, s.rssKib))
                    .toList();
            return """
                    {"mode": "%s", "ratePerSecond": %d, "allocations": %d, "baselineRssKib": %d, "peakRssKib": %d, "afterGcRssKib": %d, "reclaimLagMillis": %d, "gcCount": %d, "gcMillis": %d, "trajectory": [%s]}"""
                    .formatted(r.mode, r.ratePerSecond, r.allocations, r.baselineRssKib, r.peakRssKib,
                            r.afterGcRssKib, r.reclaimLagMillis, r.gcCount, r.gcMillis,
                            String.join(", ", traj));
        }).toList();
        var json = """
                {
                  "benchmark": "E6-cleaner-pressure",
                  "segmentBytes": %d,
                  "measureSeconds": %d,
                  "drainSeconds": %d,
                  "arch": "%s",
                  "jdk": "%s",
                  "cpuModel": "%s",
                  "results": [
                    %s
                  ]
                }
                """.formatted(SEGMENT_BYTES, cfg.measureSeconds, cfg.drainSeconds, env.arch(),
                env.jdk(), env.cpuModel(), String.join(",\n    ", rows));
        var out = dir.resolve("ffm-cleaner.json");
        Files.writeString(out, json);
        System.out.println("  " + Ansi.ok("results " + Ansi.ARROW + " " + Ansi.cyan(out.toString())));
    }

    private static Path resultsDir() {
        var override = System.getProperty("results.dir");
        var root = (override != null && !override.isBlank()) ? Path.of(override) : Path.of("build", "ffm");
        return root.resolve("ffm");
    }

    private record Sample(long tMillis, long rssKib) {
    }

    private record Result(String mode, int ratePerSecond, long allocations, long baselineRssKib,
                          long peakRssKib, long afterGcRssKib, long reclaimLagMillis,
                          long gcCount, long gcMillis, List<Sample> trajectory) {
    }

    private record Config(int ratePerSecond, int measureSeconds, int drainSeconds) {
        static Config parse(String[] args) {
            int rate = 100_000;
            int measure = 10;
            int drain = 5;
            for (var a : args) {
                if (a.equals("--quick")) {
                    measure = 3;
                    drain = 2;
                } else if (a.startsWith("--rate=")) {
                    rate = Integer.parseInt(value(a));
                } else if (a.startsWith("--measure-seconds=")) {
                    measure = Integer.parseInt(value(a));
                } else if (a.startsWith("--drain-seconds=")) {
                    drain = Integer.parseInt(value(a));
                } else if (a.startsWith("--warmup-seconds=")) {
                    continue; // accepted for a uniform lane CLI
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            return new Config(rate, measure, drain);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1);
        }
    }
}
