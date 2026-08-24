package com.pi4j.bench.common;

import oshi.SystemInfo;

/// Captured once into every result JSON so numbers are traceable to hardware.
public record EnvManifest(
        String arch,
        String jdk,
        String os,
        int cpus,
        String cpuModel,
        long totalMemBytes) {

    public static EnvManifest capture() {
        var hal = new SystemInfo().getHardware();
        return new EnvManifest(
                System.getProperty("os.arch"),
                System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                Runtime.getRuntime().availableProcessors(),
                hal.getProcessor().getProcessorIdentifier().getName().strip(),
                hal.getMemory().getTotal());
    }

    /// [#capture] but never throws: oshi's JNA hardware introspection can fail (e.g.
    /// missing reflection metadata under a native image, or a locked-down container),
    /// and env telemetry must never abort a benchmark. Falls back to the JDK's own view
    /// with an `unknown (<cause>)` CPU model so the gap is visible in the result.
    public static EnvManifest captureSafe() {
        try {
            return capture();
        } catch (Throwable t) {
            return new EnvManifest(
                    System.getProperty("os.arch"),
                    System.getProperty("java.version"),
                    System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    Runtime.getRuntime().availableProcessors(),
                    "unknown (" + t.getClass().getSimpleName() + ")",
                    -1L);
        }
    }
}
