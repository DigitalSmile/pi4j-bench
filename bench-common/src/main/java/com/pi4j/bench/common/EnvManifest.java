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
}
