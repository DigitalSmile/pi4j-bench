package com.pi4j.bench.warmup;

import com.pi4j.bench.common.EnvManifest;
import com.pi4j.bench.common.HdrRecorder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// One FFM-warmup variant's result, JSON-serialised for the merged results tree and the
/// slide-18 overlay (baseline vs AOT cache vs Leyden). Hand-rolled JSON keeps bench-warmup
/// free of a Jackson dependency.
public record WarmupReport(
        String label,
        long iterations,
        long firstCallNanos,
        long steadyIter,
        long timeToSteadyNanos,
        long steadyCostNanos,
        EnvManifest env,
        HdrRecorder.Percentiles pct) {

    public void writeJson(Path path) {
        var json = """
                {
                  "label": "%s",
                  "iterations": %d,
                  "firstCallNanos": %d,
                  "steadyIter": %d,
                  "timeToSteadyNanos": %d,
                  "steadyCostNanos": %d,
                  "meanMicros": %.4f,
                  "p50Micros": %.4f,
                  "p99Micros": %.4f,
                  "maxMicros": %.4f,
                  "arch": "%s",
                  "jdk": "%s",
                  "os": "%s"
                }
                """.formatted(label, iterations, firstCallNanos, steadyIter, timeToSteadyNanos,
                steadyCostNanos, pct.meanMicros(), pct.p50Micros(), pct.p99Micros(), pct.maxMicros(),
                env.arch(), env.jdk(), env.os());
        try {
            Files.writeString(path, json);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write warmup report " + path, e);
        }
    }
}
