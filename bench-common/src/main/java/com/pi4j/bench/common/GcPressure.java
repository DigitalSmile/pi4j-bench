package com.pi4j.bench.common;

import java.util.concurrent.locks.LockSupport;

/// Background allocator that churns short-lived garbage at a target rate to stress
/// the collector's tail during a Block C latency run (the "GC-pressure" config in
/// the plan's §6.3 matrix — {idle, GC-pressure 200 MB/s} × {G1, ZGC}). The point is
/// to expose which collector owns p99.9 and whether ZGC trims the tail.
///
/// A single daemon thread allocates 64 KiB blocks, publishes each through a
/// `volatile` sink so the JIT can't elide the allocation, then paces itself to the
/// requested throughput. Zero Pi4J deps.
public final class GcPressure implements AutoCloseable {

    private static final int BLOCK_BYTES = 64 * 1024;

    private final Thread thread;
    private volatile boolean running = true;
    // Prevents dead-code elimination of the allocations.
    @SuppressWarnings("unused")
    private volatile Object sink;

    private GcPressure(int megabytesPerSecond) {
        // Nanoseconds of wall-clock each 64 KiB block should represent at the target
        // rate; the allocator parks the remainder after each block to hold the pace.
        long blocksPerSecond = Math.max(1, (long) megabytesPerSecond * 1024 * 1024 / BLOCK_BYTES);
        long nanosPerBlock = 1_000_000_000L / blocksPerSecond;
        this.thread = Thread.ofPlatform()
                .name("gc-pressure-" + megabytesPerSecond + "mbps")
                .daemon(true)
                .unstarted(() -> churn(nanosPerBlock));
    }

    /// Starts an allocator holding ~`megabytesPerSecond` MB/s until [#close].
    public static GcPressure start(int megabytesPerSecond) {
        var pressure = new GcPressure(megabytesPerSecond);
        pressure.thread.start();
        return pressure;
    }

    private void churn(long nanosPerBlock) {
        long next = System.nanoTime();
        while (running) {
            sink = new byte[BLOCK_BYTES];
            next += nanosPerBlock;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                // Fell behind the target rate — reset the schedule instead of
                // spinning to catch up (which would overshoot the target).
                next = System.nanoTime();
            }
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1_000);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
