package com.pi4j.bench.latency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.HdrRecorder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/// The JSON result of one Block C run: environment, run config, and the percentile
/// ladder — stamped into the append-only results tree next to the `.hgrm` and warmup
/// CSV, so slide 18's numbers trace back to the exact machine and settings.
public record LatencyReport(
        String lane,
        String gc,
        boolean gcPressure,
        int gcPressureMbps,
        int rateHz,
        int warmupSeconds,
        int measureSeconds,
        long missedSamples,
        EnvManifest env,
        HdrRecorder.Percentiles latencyMicros,
        /// Stimulus-write cost bracketing every sample: the in-memory debugfs
        /// syscall offset baked into `latencyMicros` and common to both lanes.
        HdrRecorder.Percentiles stimulusWriteMicros) {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void writeJson(Path path) {
        try {
            MAPPER.writeValue(path.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write latency report " + path, e);
        }
    }
}
