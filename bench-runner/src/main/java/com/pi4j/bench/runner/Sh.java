package com.pi4j.bench.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/// Thin process helpers — replaces the old build-logic BenchShell.kt. [capture] grabs
/// (exit, merged stdout+stderr) for probes (envCheck, mock-node verification); [inherit]
/// streams the child's I/O straight to this console for the long measurement forks.
final class Sh {

    private Sh() {}

    record Result(int exit, String out) {}

    /// Run `cmd`, capturing merged output. Never throws — a spawn failure returns exit 127
    /// (mirrors BenchShell.sh) so callers branch on the code instead of catching.
    static Result capture(List<String> cmd, Path workingDir) {
        try {
            var p = new ProcessBuilder(cmd)
                    .directory(workingDir == null ? null : workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            var out = new String(p.getInputStream().readAllBytes()).trim();
            return new Result(p.waitFor(), out);
        } catch (IOException e) {
            return new Result(127, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "interrupted");
        }
    }

    static Result capture(String... cmd) {
        return capture(List.of(cmd), null);
    }

    /// Run `cmd` with its stdout/stderr inherited by this process; returns the exit code.
    static int inherit(List<String> cmd, Path workingDir) {
        try {
            return new ProcessBuilder(cmd)
                    .directory(workingDir == null ? null : workingDir.toFile())
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted waiting for " + cmd.getFirst(), e);
        }
    }
}
