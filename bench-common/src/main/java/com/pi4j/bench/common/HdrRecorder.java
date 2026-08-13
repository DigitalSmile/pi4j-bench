package com.pi4j.bench.common;

import java.io.PrintStream;
import org.HdrHistogram.Histogram;

/// Loss-less latency recording for Block C (µs, 3 significant figures).
public final class HdrRecorder {

    private final Histogram histogram;

    public HdrRecorder() {
        // 1 ns .. 60 s range, 3 s.f. — covers listener latency incl. GC tail.
        this.histogram = new Histogram(1L, 60_000_000_000L, 3);
    }

    public void recordNanos(long nanos) {
        histogram.recordValue(Math.max(1L, nanos));
    }

    public void reset() {
        histogram.reset();
    }

    /// p50/p99/p99.9 in microseconds — the percentile ladder for slide 18.
    public double percentileMicros(double percentile) {
        return histogram.getValueAtPercentile(percentile) / 1_000.0;
    }

    public void exportHgrm(PrintStream out) {
        histogram.outputPercentileDistribution(out, 1_000.0); // scale ns → µs
    }
}
