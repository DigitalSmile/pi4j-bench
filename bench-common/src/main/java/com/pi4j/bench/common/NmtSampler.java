package com.pi4j.bench.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/// Reads the JVM's own Native Memory Tracking total via `jcmd <pid> VM.native_memory
/// summary` for the Block D memory trajectory. NMT complements VmRSS ([RssSampler]):
/// RSS is the OS-visible resident footprint, NMT the JVM's accounted commit — the
/// #628 arena leak shows up in both as a ramp (`ofAuto`) vs a flat line (`ofConfined`).
///
/// Requires `-XX:NativeMemoryTracking=summary` on the launch (set in bench-memory's
/// JVM args); returns -1 when NMT is off or `jcmd` is unavailable. Zero Pi4J deps.
public final class NmtSampler {

    // "Total: reserved=1234567KB, committed=234567KB"
    private static final Pattern TOTAL =
            Pattern.compile("Total:\\s*reserved=\\d+KB,\\s*committed=(\\d+)KB");

    private final String jcmd;
    private final String pid;

    public NmtSampler() {
        this.jcmd = locateJcmd();
        this.pid = String.valueOf(ProcessHandle.current().pid());
    }

    /// Total committed memory in KiB per NMT, or -1 if unavailable.
    public long committedKib() {
        if (jcmd == null) {
            return -1L;
        }
        try {
            var process = new ProcessBuilder(jcmd, pid, "VM.native_memory", "summary")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            process.waitFor();
            var matcher = TOTAL.matcher(output);
            return matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
        } catch (IOException | NumberFormatException e) {
            return -1L;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        }
    }

    /// `jcmd` lives next to the running JVM; fall back to the PATH name if absent.
    private static String locateJcmd() {
        var home = System.getProperty("java.home");
        if (home != null) {
            var candidate = Path.of(home, "bin", "jcmd");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }
}
