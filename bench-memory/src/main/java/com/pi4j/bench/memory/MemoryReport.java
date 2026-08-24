package com.pi4j.bench.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pi4j.bench.common.EnvManifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/// The JSON result of one Block D run: the RSS/NMT trajectory over 1 M I2C reads plus
/// its endpoints. Slide 19–20's story is `rssDeltaKib` ≈ 0 for `--mode=fixed`
/// (per-call `Arena.ofConfined`, 4.0.2) vs a large positive ramp for
/// `--mode=issue628` (shared `Arena.ofAuto`, pre-fix 4.0.1).
public record MemoryReport(
        String mode,
        String pi4jVersion,
        long totalOps,
        long snapshotEvery,
        long rssStartKib,
        long rssEndKib,
        long rssDeltaKib,
        long nmtStartKib,
        long nmtEndKib,
        long nmtDeltaKib,
        EnvManifest env,
        List<Snapshot> trajectory) {

    /// One point on the trajectory: resident (RSS) and NMT-committed footprint at `op`.
    public record Snapshot(long op, long rssKib, long nmtCommittedKib) {
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void writeJson(Path path) {
        try {
            MAPPER.writeValue(path.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write memory report " + path, e);
        }
    }
}
