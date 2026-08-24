package com.pi4j.bench.runner;

import com.pi4j.bench.common.Ansi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Provenance-stamps a completed run's own directory (results/<arch>/<lane>/<timestamp>/).
/// The lanes already wrote straight into that per-run dir (and its per-GC subdirs), so there
/// is nothing to copy — this only drops the V3 native fingerprints and a run-manifest.json
/// (git SHA, pi4j versions, JDK, kernel, the GC sweep) alongside them. Nothing is overwritten
/// because every invocation gets a fresh timestamped dir.
final class ReportMerger {

    private final Path root;
    private final String arch;
    private final Config cfg;

    ReportMerger(Path root, String arch, Config cfg) {
        this.root = root;
        this.arch = arch;
        this.cfg = cfg;
    }

    void merge(Path runDir, String ts, List<String> gcs) throws IOException {
        if (cfg.dryRun()) {
            System.out.println("  [dry-run] mergeReports → " + root.relativize(runDir));
            return;
        }
        Files.createDirectories(runDir);

        // V3 native provenance — the exact JNI binary each gpiod/linuxfs number ran against.
        var nativeLibs = root.resolve("native-v3/build/libs/" + arch);
        if (Files.isDirectory(nativeLibs)) {
            try (Stream<Path> fp = Files.list(nativeLibs)) {
                for (var f : fp.filter(p -> p.getFileName().toString().startsWith("fingerprint")).toList()) {
                    Files.copy(f, runDir.resolve(f.getFileName().toString()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        var gitSha = Sh.capture("git", "rev-parse", "--short", "HEAD");
        var sha = gitSha.exit() == 0 && !gitSha.out().isBlank() ? gitSha.out() : "nogit";
        var kernel = Sh.capture("uname", "-r").out();
        var gcArray = gcs.stream().map(g -> "\"" + g + "\"").collect(Collectors.joining(", "));
        Files.writeString(runDir.resolve("run-manifest.json"), """
                {
                  "timestamp": "%s",
                  "arch": "%s",
                  "lane": "%s",
                  "gcs": [%s],
                  "gcPressure": %s,
                  "gcPressureMbps": %d,
                  "gitSha": "%s",
                  "pi4jV4": "%s",
                  "pi4jV3": "%s",
                  "jdk": "%s",
                  "kernel": "%s"
                }
                """.formatted(ts, arch, cfg.lane(), gcArray, cfg.gcPressure(), cfg.gcPressureMbps(), sha,
                System.getProperty("pi4j.v4", "unknown"),
                System.getProperty("pi4j.v3", "unknown"),
                System.getProperty("java.version"), kernel));

        System.out.println("  " + Ansi.ok("snapshot " + Ansi.ARROW + " " + Ansi.cyan(root.relativize(runDir).toString())));
    }
}
