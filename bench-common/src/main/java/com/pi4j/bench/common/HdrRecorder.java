package com.pi4j.bench.common;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public long count() {
        return histogram.getTotalCount();
    }

    /// p50/p99/p99.9 in microseconds — the percentile ladder for slide 18.
    public double percentileMicros(double percentile) {
        return histogram.getValueAtPercentile(percentile) / 1_000.0;
    }

    /// The full ladder + mean/max in microseconds, ready to serialize into a result.
    public Percentiles percentiles() {
        return new Percentiles(
                histogram.getTotalCount(),
                histogram.getMean() / 1_000.0,
                percentileMicros(50),
                percentileMicros(90),
                percentileMicros(99),
                percentileMicros(99.9),
                percentileMicros(99.99),
                histogram.getMaxValue() / 1_000.0);
    }

    public void exportHgrm(PrintStream out) {
        histogram.outputPercentileDistribution(out, 1_000.0); // scale ns → µs
    }

    /// Writes the `.hgrm` percentile distribution (µs) to a file for HdrHistogram's
    /// plotters and the append-only results tree.
    public void exportHgrm(Path path) {
        try (var out = new PrintStream(Files.newOutputStream(path))) {
            exportHgrm(out);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write histogram " + path, e);
        }
    }

    /// Immutable percentile snapshot in microseconds (JSON-friendly for reports).
    public record Percentiles(
            long count,
            double meanMicros,
            double p50Micros,
            double p90Micros,
            double p99Micros,
            double p999Micros,
            double p9999Micros,
            double maxMicros) {
    }
}
