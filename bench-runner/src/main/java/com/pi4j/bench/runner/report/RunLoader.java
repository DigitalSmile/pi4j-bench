package com.pi4j.bench.runner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/// Reads one run directory (results/<arch>/<lane>/<timestamp>/, flat or with per-GC
/// subdirs) into a compact JSON model the HTML report embeds and renders client-side.
/// Only the numbers the charts need are extracted — the JMH `rawData` arrays and the full
/// HDR histograms are dropped, and long warmup curves are downsampled — so the report stays
/// small and self-contained.
public final class RunLoader {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> GC_NAMES =
            List.of("g1", "zgc", "parallel", "serial", "shenandoah", "epsilon");

    private RunLoader() {
    }

    /// Build the model for a single run dir: `{ meta:{…}, lanes:{ <gc>:{jmh,latency,memory,warmup} } }`.
    public static ObjectNode load(Path runDir) {
        var run = M.createObjectNode();
        var meta = meta(runDir);
        run.set("meta", meta);

        var lanes = run.putObject("lanes");
        var gcDirs = gcDirs(runDir);
        if (gcDirs.isEmpty()) {
            // Flat single-GC run (or a legacy dir): the run dir itself is the one lane dir.
            var gc = firstGc(meta);
            lanes.set(gc, laneData(runDir));
        } else {
            for (var d : gcDirs) lanes.set(d.getFileName().toString(), laneData(d));
        }
        // If the manifest never listed collectors, derive them from what we loaded.
        if (!meta.hasNonNull("gcs") || meta.get("gcs").isEmpty()) {
            var arr = meta.putArray("gcs");
            lanes.fieldNames().forEachRemaining(arr::add);
        }
        enrich(meta, runDir, run);
        return run;
    }

    /// Backfill the specific JVM build (VM name/version, JDK version, JVM path) from a JMH
    /// result, and the concrete pi4j versions the memory lanes recorded — details the run
    /// manifest alone doesn't carry, so the report header can be precise.
    private static void enrich(ObjectNode meta, Path runDir, ObjectNode run) {
        var jmh = findFirst(runDir, "v4-jmh.json", "v3-jmh.json");
        if (jmh != null) {
            var arr = readJson(jmh);
            if (arr != null && arr.isArray() && !arr.isEmpty()) {
                var b = arr.get(0);
                copy(b, meta, "jdkVersion", "vmName", "vmVersion");
                if (b.hasNonNull("jvm")) meta.put("jvmPath", b.get("jvm").asText());
            }
        }
        var seen = new LinkedHashSet<String>();
        run.path("lanes").forEach(lane -> lane.path("memory").forEach(m -> {
            var v = m.path("pi4jVersion").asText("");
            if (!v.isBlank()) seen.add(v);
        }));
        if (!seen.isEmpty()) {
            var arr = meta.putArray("memPi4j");
            seen.forEach(arr::add);
        }
    }

    private static Path findFirst(Path root, String... names) {
        var set = java.util.Set.of(names);
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> s = Files.walk(root, 3)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> set.contains(p.getFileName().toString()))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstGc(ObjectNode meta) {
        var gcs = meta.get("gcs");
        if (gcs != null && gcs.isArray() && !gcs.isEmpty()) return gcs.get(0).asText();
        return "run";
    }

    /// Subdirectories named after a collector (a GC sweep). Empty for a flat single-GC run.
    private static List<Path> gcDirs(Path runDir) {
        if (!Files.isDirectory(runDir)) return List.of();
        try (Stream<Path> s = Files.list(runDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> GC_NAMES.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static ObjectNode meta(Path runDir) {
        var meta = M.createObjectNode();
        var rm = readJson(runDir.resolve("run-manifest.json"));
        if (rm != null) {
            copy(rm, meta, "timestamp", "arch", "lane", "gitSha", "pi4jV4", "pi4jV3", "jdk", "kernel");
            if (rm.has("gcs")) meta.set("gcs", rm.get("gcs"));
            if (rm.has("gcPressure")) meta.set("gcPressure", rm.get("gcPressure"));
            if (rm.has("gcPressureMbps")) meta.set("gcPressureMbps", rm.get("gcPressureMbps"));
        }
        var em = readJson(runDir.resolve("env-manifest.json"));
        if (em != null) {
            if (!meta.has("arch") && em.has("arch")) meta.set("arch", em.get("arch"));
            if (!meta.has("lane") && em.has("lane")) meta.set("lane", em.get("lane"));
            var host = meta.putObject("host");
            copy(em, host, "cpuModel", "os");
            if (em.has("cpus")) host.set("cpus", em.get("cpus"));
            if (em.has("totalMemBytes")) host.set("totalMemBytes", em.get("totalMemBytes"));
        }
        if (!meta.has("timestamp")) meta.put("timestamp", runDir.getFileName().toString());
        return meta;
    }

    private static ObjectNode laneData(Path dir) {
        var lane = M.createObjectNode();
        lane.set("jmh", jmh(dir));
        lane.set("latency", latency(dir));
        lane.set("memory", memory(dir));
        lane.set("warmup", warmup(dir));
        lane.set("ffm", ffm(dir));
        return lane;
    }

    /// Block E deep-dive: ffm/ffm-{vthreads,safepoint,cleaner}.json (custom schemas) →
    /// `{ vthreads:{…}, safepoint:{…}, cleaner:{…} }`, each carrying its `results` array verbatim
    /// (small enough to embed). Absent files are simply omitted so the report degrades gracefully.
    private static ObjectNode ffm(Path dir) {
        var out = M.createObjectNode();
        var ffmDir = dir.resolve("ffm");
        if (!Files.isDirectory(ffmDir)) return out;
        putFfm(out, "vthreads", ffmDir.resolve("ffm-vthreads.json"));
        putFfm(out, "safepoint", ffmDir.resolve("ffm-safepoint.json"));
        putFfm(out, "cleaner", ffmDir.resolve("ffm-cleaner.json"));
        return out;
    }

    private static void putFfm(ObjectNode out, String key, Path file) {
        var r = readJson(file);
        if (r != null && r.isObject()) out.set(key, r);
    }

    /// JMH: v4-jmh.json (group "v4") + v3-jmh.json (group "v3") → [{name,group,score,error,unit,mode}].
    private static ArrayNode jmh(Path dir) {
        var out = M.createArrayNode();
        jmhFile(dir.resolve("v4-jmh.json"), "v4", out);
        jmhFile(dir.resolve("v3-jmh.json"), "v3", out);
        return out;
    }

    private static void jmhFile(Path file, String group, ArrayNode out) {
        var root = readJson(file);
        if (root == null || !root.isArray()) return;
        for (var b : root) {
            var pm = b.get("primaryMetric");
            if (pm == null) continue;
            var o = out.addObject();
            o.put("name", b.path("benchmark").asText());
            o.put("group", group);
            o.put("mode", b.path("mode").asText());
            o.put("score", pm.path("score").asDouble());
            o.put("error", pm.path("scoreError").isNumber() ? pm.get("scoreError").asDouble() : 0.0);
            o.put("unit", pm.path("scoreUnit").asText());
            // Params (e.g. bytes=4096, allocMbps=200) distinguish otherwise-identical benchmark
            // names — the Block E micro-benchmarks (E7/E8/E-GC) are parameterised.
            if (b.path("params").isObject()) {
                var ps = new StringBuilder();
                b.get("params").fields().forEachRemaining(e -> {
                    if (ps.length() > 0) ps.append(", ");
                    ps.append(e.getKey()).append('=').append(e.getValue().asText());
                });
                if (ps.length() > 0) o.put("params", ps.toString());
            }
        }
    }

    /// Latency: every latency/**/<lane>.json (LatencyReport) → compact percentile record.
    private static ArrayNode latency(Path dir) {
        var out = M.createArrayNode();
        var latDir = dir.resolve("latency");
        if (!Files.isDirectory(latDir)) return out;
        try (Stream<Path> s = Files.walk(latDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                var r = readJson(p);
                if (r == null || !r.has("latencyMicros")) return;
                var o = out.addObject();
                o.put("lane", r.path("lane").asText());
                o.put("gc", r.path("gc").asText());
                o.put("gcPressure", r.path("gcPressure").asBoolean(false));
                o.put("mbps", r.path("gcPressureMbps").asInt(0));
                o.put("rateHz", r.path("rateHz").asInt(0));
                o.put("missed", r.path("missedSamples").asInt(0));
                var lm = r.get("latencyMicros");
                o.put("count", lm.path("count").asLong());
                o.set("p", percentiles(lm));
                if (r.has("stimulusWriteMicros")) o.set("write", percentiles(r.get("stimulusWriteMicros")));
            });
        } catch (IOException ignore) {
            // partial results are fine
        }
        return out;
    }

    private static ObjectNode percentiles(JsonNode n) {
        var p = M.createObjectNode();
        p.put("mean", n.path("meanMicros").asDouble());
        p.put("p50", n.path("p50Micros").asDouble());
        p.put("p90", n.path("p90Micros").asDouble());
        p.put("p99", n.path("p99Micros").asDouble());
        p.put("p999", n.path("p999Micros").asDouble());
        p.put("p9999", n.path("p9999Micros").asDouble());
        p.put("max", n.path("maxMicros").asDouble());
        return p;
    }

    /// Memory: memory/*.json (MemoryReport, trajectory embedded) → {mode,version,deltas,traj}.
    private static ArrayNode memory(Path dir) {
        var out = M.createArrayNode();
        var memDir = dir.resolve("memory");
        if (!Files.isDirectory(memDir)) return out;
        try (Stream<Path> s = Files.list(memDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                var r = readJson(p);
                if (r == null || !r.has("trajectory")) return;
                var o = out.addObject();
                o.put("mode", r.path("mode").asText());
                o.put("pi4jVersion", r.path("pi4jVersion").asText());
                o.put("totalOps", r.path("totalOps").asLong());
                o.put("rssStartKib", r.path("rssStartKib").asLong());
                o.put("rssEndKib", r.path("rssEndKib").asLong());
                o.put("rssDeltaKib", r.path("rssDeltaKib").asLong());
                o.put("nmtDeltaKib", r.path("nmtDeltaKib").asLong());
                var traj = o.putArray("traj");
                for (var t : r.get("trajectory")) {
                    var row = traj.addArray();
                    row.add(t.path("op").asLong());
                    row.add(t.path("rssKib").asLong());
                    row.add(t.path("nmtCommittedKib").asLong());
                }
            });
        } catch (IOException ignore) {
            // partial results are fine
        }
        return out;
    }

    /// Warmup: warmup/warmup-v4-<label>.json + its downsampled -curve.csv.
    private static ArrayNode warmup(Path dir) {
        var out = M.createArrayNode();
        var wDir = dir.resolve("warmup");
        if (!Files.isDirectory(wDir)) return out;
        try (Stream<Path> s = Files.list(wDir)) {
            s.filter(p -> { var n = p.getFileName().toString(); return n.endsWith(".json") && n.startsWith("warmup-"); })
                    .sorted().forEach(p -> {
                var r = readJson(p);
                if (r == null || !r.has("label")) return;
                var o = out.addObject();
                var label = r.path("label").asText();
                o.put("label", label);
                o.put("firstCallNanos", r.path("firstCallNanos").asLong());
                o.put("steadyIter", r.path("steadyIter").asLong());
                o.put("timeToSteadyNanos", r.path("timeToSteadyNanos").asLong());
                o.put("steadyCostNanos", r.path("steadyCostNanos").asLong());
                o.put("meanMicros", r.path("meanMicros").asDouble());
                o.put("p50", r.path("p50Micros").asDouble());
                o.put("p99", r.path("p99Micros").asDouble());
                o.put("max", r.path("maxMicros").asDouble());
                o.set("curve", curve(wDir.resolve("warmup-v4-" + label + "-curve.csv")));
            });
        } catch (IOException ignore) {
            // partial results are fine
        }
        return out;
    }

    /// Read a two-column CSV (x,y) into [[x,y]…], downsampled to at most `MAX_CURVE` points.
    private static final int MAX_CURVE = 400;

    private static ArrayNode curve(Path csv) {
        var out = M.createArrayNode();
        if (!Files.isRegularFile(csv)) return out;
        try {
            var lines = Files.readAllLines(csv);
            var rows = lines.stream().skip(1)          // header
                    .map(l -> l.split(","))
                    .filter(a -> a.length >= 2)
                    .toList();
            int n = rows.size();
            int step = Math.max(1, n / MAX_CURVE);
            for (int i = 0; i < n; i += step) addPoint(out, rows.get(i));
            if (n > 0 && (n - 1) % step != 0) addPoint(out, rows.get(n - 1)); // always keep the last point
        } catch (IOException | NumberFormatException ignore) {
            // best-effort curve
        }
        return out;
    }

    private static void addPoint(ArrayNode out, String[] a) {
        var row = out.addArray();
        row.add(Long.parseLong(a[0].trim()));
        row.add(Long.parseLong(a[1].trim()));
    }

    private static void copy(JsonNode from, ObjectNode to, String... keys) {
        for (var k : keys) if (from.hasNonNull(k)) to.set(k, from.get(k));
    }

    private static JsonNode readJson(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            return M.readTree(p.toFile());
        } catch (IOException e) {
            System.err.println("report: skipping unreadable " + p + " (" + e.getMessage() + ")");
            return null;
        }
    }

    static ObjectMapper mapper() {
        return M;
    }

    /// Serialise any node to its JSON text (used to inject the embedded data blob).
    static String toJson(JsonNode node) {
        try {
            return M.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
