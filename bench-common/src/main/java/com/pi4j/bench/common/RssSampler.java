package com.pi4j.bench.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Reads VmRSS from /proc/self/status for the Block D memory trajectory.
public final class RssSampler {

    private static final Path SELF_STATUS = Path.of("/proc/self/status");

    private RssSampler() {}

    /// Resident set size in kibibytes, or -1 if unavailable (non-Linux).
    public static long vmRssKib() {
        try {
            for (var line : Files.readAllLines(SELF_STATUS)) {
                if (line.startsWith("VmRSS:")) {
                    // "VmRSS:\t  123456 kB"
                    return Long.parseLong(line.replaceAll("\\D", ""));
                }
            }
            return -1L;
        } catch (IOException | NumberFormatException e) {
            return -1L;
        }
    }
}
