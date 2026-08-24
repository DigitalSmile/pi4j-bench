package com.pi4j.bench.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// One forkable benchmark lane, resolved by Gradle's `writeLaneSpec` into
/// `build/lanes/<name>.properties` and read back here. The orchestrator never holds a
/// lane's classpath itself (the v3/v4 `pi4j-core` GAVs can't coexist — plan Appendix A);
/// it forks a child `java` process per lane using [classpath].
///
/// A [Type#JMH] lane is launched as `java -cp <cp> org.openjdk.jmh.Main …` with the
/// per-JVM flags handed to JMH's *forked* measurement JVMs via `-jvmArgsAppend`; a
/// [Type#MAIN] lane (LatencyRunner/MemoryRunner) applies [jvmArgs]/[sysProps] to the
/// launched JVM directly.
public record LaneSpec(
        String name,
        Type type,
        String mainClass,
        Path workingDir,
        List<String> jvmArgs,
        Map<String, String> sysProps,
        List<String> args,
        String classpath,
        String binary) {

    /// JMH (`java -cp … org.openjdk.jmh.Main`), MAIN (`java … <MainClass>`), or NATIVE
    /// (a self-contained GraalVM AOT binary run directly — no `java`, no classpath).
    public enum Type { JMH, MAIN, NATIVE }

    /// Reads every `*.properties` in `dir` (sorted by filename) into lane specs. In a
    /// scp'd bundle the specs carry paths relative to the bundle root (`baseDir`); in a
    /// dev build they are absolute (`baseDir == null`, a no-op).
    public static List<LaneSpec> loadAll(Path dir, Path baseDir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("no lane specs under " + dir + " — did :collectLaneSpecs run?");
        }
        try (Stream<Path> files = Files.list(dir)) {
            var specs = new ArrayList<LaneSpec>();
            for (var f : files.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                specs.add(load(f, baseDir));
            }
            return List.copyOf(specs);
        }
    }

    public static LaneSpec load(Path file, Path baseDir) throws IOException {
        var props = new Properties();
        try (var r = Files.newBufferedReader(file)) {
            props.load(r);
        }
        var name = file.getFileName().toString().replaceFirst("\\.properties$", "");
        var type = switch (props.getProperty("type", "main")) {
            case "jmh" -> Type.JMH;
            case "native" -> Type.NATIVE;
            default -> Type.MAIN;
        };
        return new LaneSpec(
                name,
                type,
                props.getProperty("mainClass"),
                resolveDir(props.getProperty("workingDir", "."), baseDir),
                words(props.getProperty("jvmArgs")),
                resolvePathProps(pairs(props.getProperty("sysProps")), baseDir),
                words(props.getProperty("args")),
                resolveClasspath(props.getProperty("classpath", ""), baseDir),
                resolveBinary(props.getProperty("binary"), baseDir));
    }

    /// The NATIVE lane's AOT binary path — expand `${bench.arch}` (per-board) and rebase a
    /// relative path onto the bundle root, like the classpath entries.
    private static String resolveBinary(String binary, Path baseDir) {
        if (binary == null || binary.isBlank()) return null;
        var raw = binary.replace("${bench.arch}", archLabel());
        var p = Path.of(raw);
        return (baseDir == null || p.isAbsolute()) ? raw : baseDir.resolve(p).toString();
    }

    private static Path resolveDir(String dir, Path baseDir) {
        var p = Path.of(dir);
        return (baseDir == null || p.isAbsolute()) ? p : baseDir.resolve(p);
    }

    /// Rebase each relative classpath entry onto the bundle root; absolutes pass through.
    private static String resolveClasspath(String cp, Path baseDir) {
        if (baseDir == null || cp.isBlank()) return cp;
        var sep = System.getProperty("path.separator");
        var out = new ArrayList<String>();
        for (var e : cp.split(Pattern.quote(sep))) {
            var p = Path.of(e);
            out.add((p.isAbsolute() ? p : baseDir.resolve(p)).toString());
        }
        return String.join(sep, out);
    }

    /// pi4j.library.path is a filesystem path; when running from a bundle, expand the
    /// `${bench.arch}` token to the arch we're actually running on (so one multi-arch bundle
    /// resolves lanes/natives/<arch> per board) and rebase the relative path onto the bundle.
    private static Map<String, String> resolvePathProps(Map<String, String> props, Path baseDir) {
        if (baseDir == null || !props.containsKey("pi4j.library.path")) return props;
        var out = new LinkedHashMap<>(props);
        var raw = out.get("pi4j.library.path").replace("${bench.arch}", archLabel());
        var p = Path.of(raw);
        out.put("pi4j.library.path", (p.isAbsolute() ? p : baseDir.resolve(p)).toString());
        return Map.copyOf(out);
    }

    /// Normalise this JVM's `os.arch` to the label the bundle stages natives under
    /// (amd64/arm64/riscv64), matching native-v3's target labels.
    static String archLabel() {
        return switch (System.getProperty("os.arch", "")) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            case String a -> a; // riscv64, etc. — already the label we use
        };
    }

    private static List<String> words(String s) {
        if (s == null || s.isBlank()) return List.of();
        return List.of(s.trim().split("\\s+"));
    }

    private static Map<String, String> pairs(String s) {
        if (s == null || s.isBlank()) return Map.of();
        var out = new LinkedHashMap<String, String>();
        for (var kv : s.split(";")) {
            var eq = kv.indexOf('=');
            if (eq > 0) out.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        return Map.copyOf(out);
    }
}
