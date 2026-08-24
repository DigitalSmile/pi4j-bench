package com.pi4j.bench.runner.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Renders the self-contained dark-theme HTML report by injecting a JSON data blob into the
/// packaged `report/template.html` (inline CSS + vanilla-JS SVG charts — no CDN, so it opens
/// offline on a board). Two modes: `single` (one run, written next to its results) and
/// `compare` (N runs side by side).
public final class HtmlReport {

    private HtmlReport() {
    }

    /// Build `<runDir>/report.html` for a just-finished run. Returns the written path, or null
    /// if the run held no parseable results.
    public static Path writeSingle(Path runDir) {
        return writeSingle(runDir, runDir.resolve("report.html"));
    }

    /// As [#writeSingle(Path)] but to an explicit output path.
    public static Path writeSingle(Path runDir, Path out) {
        var run = RunLoader.load(runDir);
        if (isEmpty(run)) return null;
        var title = "pi4j-bench · " + run.path("meta").path("timestamp").asText(runDir.getFileName().toString());
        write(out, render("single", run, title));
        return out;
    }

    /// Build a comparison report over several run dirs into `out`. Each run is labelled by its
    /// timestamp (disambiguated by parent dirs when timestamps collide).
    public static Path writeCompare(List<Path> runDirs, Path out) {
        var root = RunLoader.mapper().createObjectNode();
        var runs = root.putArray("runs");
        var labels = labels(runDirs);
        for (int i = 0; i < runDirs.size(); i++) {
            var run = RunLoader.load(runDirs.get(i));
            if (isEmpty(run)) {
                System.err.println("report: no results in " + runDirs.get(i) + " — skipped");
                continue;
            }
            run.put("label", labels.get(i));
            runs.add(run);
        }
        if (runs.isEmpty()) throw new IllegalStateException("no parseable runs among " + runDirs);
        write(out, render("compare", root, "pi4j-bench · comparison (" + runs.size() + " runs)"));
        return out;
    }

    private static boolean isEmpty(JsonNode run) {
        var lanes = run.path("lanes");
        if (lanes.isMissingNode() || lanes.isEmpty()) return true;
        for (var lane : lanes) {
            if (!lane.path("jmh").isEmpty() || !lane.path("latency").isEmpty()
                    || !lane.path("memory").isEmpty() || !lane.path("warmup").isEmpty()
                    || !lane.path("ffm").isEmpty()) return false;
        }
        return true;
    }

    /// Distinct, short labels for the runs — timestamps, or `<parent>/<timestamp>` on collision.
    private static List<String> labels(List<Path> dirs) {
        var ts = dirs.stream().map(d -> d.getFileName().toString()).toList();
        boolean unique = ts.stream().distinct().count() == ts.size();
        if (unique) return ts;
        return dirs.stream().map(d -> {
            var parent = d.getParent() != null ? d.getParent().getFileName() : null;
            return (parent != null ? parent + "/" : "") + d.getFileName();
        }).toList();
    }

    private static String render(String mode, JsonNode data, String title) {
        var tpl = template();
        // Order matters: __DATA__ value is raw JSON and may itself contain the literal
        // "__MODE__"/"__TITLE__" only as data (escaped), so substitute the markers first.
        return tpl
                .replace("__TITLE__", escapeHtml(title))
                .replace("\"__MODE__\"", '"' + mode + '"')
                .replace("/*__DATA__*/ null", RunLoader.toJson(data));
    }

    private static String template() {
        try (var in = HtmlReport.class.getResourceAsStream("/report/template.html")) {
            if (in == null) throw new IllegalStateException("report/template.html not on classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path out, String html) {
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, html);
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + out, e);
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
