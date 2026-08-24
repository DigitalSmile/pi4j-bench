package com.pi4j.bench.runner;

import com.pi4j.bench.common.Ansi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Launches a child `java` process for one measurement lane, CPU-pinned. This is where
/// the taskset affinity that `run-matrix.sh` used to wrap the whole `gradlew` launcher
/// now lives — applied per fork, so each lane's JVM (and, for JMH, the JVMs it forks in
/// turn) inherits the pinned cores. Uses this JVM's own `java` (JDK 25) so the children
/// match the orchestrator's toolchain.
final class Forker {

    private final String javaExe;
    private final List<String> tasksetPrefix;
    private final boolean dryRun;

    Forker(String cpus, String jdkHome, boolean dryRun) {
        // Pick the JDK for the forked lanes: an explicit config/CLI jdk wins; else this
        // JVM's java.home (dev run / fat jar); else bare `java` from PATH — the only option
        // in a GraalVM native image, which has no bundled JDK (java.home is null there).
        var home = (jdkHome != null && !jdkHome.isBlank()) ? jdkHome : System.getProperty("java.home");
        this.javaExe = (home == null || home.isBlank())
                ? "java"
                : Path.of(home, "bin", "java").toString();
        this.tasksetPrefix = (cpus == null || cpus.isBlank())
                ? List.of()
                : List.of("taskset", "-c", cpus);
        this.dryRun = dryRun;
    }

    /// The `java` binary child commands should start with.
    String java() {
        return javaExe;
    }

    /// Run `javaCommand` (already starting with [#java]) in `workingDir`; returns its exit
    /// code (0 in dry-run). Output is inherited so JMH/runner progress streams live.
    int fork(List<String> javaCommand, Path workingDir) {
        var full = new ArrayList<String>(tasksetPrefix);
        full.addAll(javaCommand);
        // The full fork command is verbose but load-bearing (repro): dim it so it doesn't
        // drown the lane's own output, and flag the CPU pinning when present.
        var pin = tasksetPrefix.isEmpty() ? "" : Ansi.yellow("[pinned " + tasksetPrefix.getLast() + "] ");
        System.out.println("  " + Ansi.cyan(Ansi.ARROW) + " " + pin + Ansi.dim(String.join(" ", full)));
        System.out.println("    " + Ansi.dim("cwd " + workingDir));
        return dryRun ? 0 : Sh.inherit(full, workingDir);
    }
}
