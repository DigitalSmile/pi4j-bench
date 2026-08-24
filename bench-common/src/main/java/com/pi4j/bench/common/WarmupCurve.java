package com.pi4j.bench.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Records the first N raw edge→listener latencies of a run so the warmup curve
/// (call #1 / #100 / #10⁴) can be plotted — slide 18's "JIT warms up; native image
/// stays flat" story (plan §6.3.5). Unlike the HdrHistogram this keeps *ordered*
/// raw samples, not a percentile digest, so the shape over time survives.
///
/// Pre-sized and append-only up to [#capacity]; further samples are ignored. Zero
/// Pi4J deps.
public final class WarmupCurve {

    private final long[] nanos;
    private int count;

    public WarmupCurve(int capacity) {
        this.nanos = new long[capacity];
    }

    public int capacity() {
        return nanos.length;
    }

    public int count() {
        return count;
    }

    public boolean isFull() {
        return count >= nanos.length;
    }

    /// Appends one raw latency (ns); a no-op once [#capacity] is reached.
    public void record(long latencyNanos) {
        if (count < nanos.length) {
            nanos[count++] = latencyNanos;
        }
    }

    /// The recorded samples in order (a trimmed copy) — for steady-state analysis.
    public long[] toArray() {
        return java.util.Arrays.copyOf(nanos, count);
    }

    /// Writes `sample,latencyNanos` CSV (1-based sample index) for the plotter.
    public void exportCsv(Path path) {
        List<String> lines = new ArrayList<>(count + 1);
        lines.add("sample,latencyNanos");
        for (int i = 0; i < count; i++) {
            lines.add((i + 1) + "," + nanos[i]);
        }
        try {
            Files.write(path, lines);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write warmup curve " + path, e);
        }
    }
}
