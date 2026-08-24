package com.pi4j.bench.runner;

import java.util.List;

/// Immutable carrier for the parsed run options — built by [BenchRunner] from its picocli
/// fields and threaded through EnvCheck / MockLifecycle / ReportMerger / the fork builders.
public record Config(
        String lane,
        boolean quick,
        String gc,
        boolean jfr,
        List<String> profilers,
        String cpus,
        String jhiccup,
        List<String> lanes,
        boolean nativeImage,
        String leydenJdk,
        boolean gcPressure,
        int gcPressureMbps,
        boolean dryRun) {

    public boolean isMock() {
        return !"hw".equalsIgnoreCase(lane);
    }

    /// A copy of this config with a different collector — used to fan the whole matrix out
    /// over a GC sweep (`--gc-matrix` / a preset's `gcs`), one collector per iteration.
    public Config withGc(String newGc) {
        return new Config(lane, quick, newGc, jfr, profilers, cpus, jhiccup, lanes,
                nativeImage, leydenJdk, gcPressure, gcPressureMbps, dryRun);
    }

    /// True when `laneName` is in the requested set — an empty [#lanes] means "run all".
    public boolean wants(String laneName) {
        return lanes.isEmpty() || lanes.contains(laneName);
    }

    /// The JVM flag for the selected GC, applied to EVERY forked lane (not just latency).
    public String gcFlag() {
        return switch (gc == null ? "g1" : gc.toLowerCase()) {
            case "zgc" -> "-XX:+UseZGC";
            case "parallel" -> "-XX:+UseParallelGC";
            case "serial" -> "-XX:+UseSerialGC";
            case "shenandoah" -> "-XX:+UseShenandoahGC";
            case "epsilon" -> "-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC";
            default -> "-XX:+UseG1GC";
        };
    }
}
