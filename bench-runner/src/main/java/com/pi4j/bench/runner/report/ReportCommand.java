package com.pi4j.bench.runner.report;

import com.pi4j.bench.common.Ansi;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/// `bench report [dirs…]` — build the dark-theme HTML report from already-captured results.
/// One dir → a single-run report; several → a side-by-side comparison (deltas, regressions,
/// stale-lane detection). With no dirs it auto-discovers the most recent runs under the
/// results tree, so `bench report` after a couple of runs Just Works.
@Command(name = "report",
        mixinStandardHelpOptions = true,
        description = "Build the dark-theme HTML report: one run, or a comparison across runs.",
        footerHeading = "%nExamples:%n",
        footer = {
                "  bench report                                 # compare the latest 2 runs under ./results",
                "  bench report --last 4                        # compare the latest 4",
                "  bench report results/amd64/mock/20260814-171546   # single-run report → that dir/report.html",
                "  bench report RUN_A RUN_B -o compare.html     # compare two explicit runs",
        })
public final class ReportCommand implements Callable<Integer> {

    @Parameters(paramLabel = "<run-dir>", arity = "0..*",
            description = "Run directories (results/<arch>/<lane>/<timestamp>). None → auto-discover the latest.")
    List<Path> dirs = new ArrayList<>();

    @Option(names = {"-o", "--out"}, paramLabel = "<file>",
            description = "Output HTML path. Default: <run>/report.html (single) or ./report-compare.html.")
    Path out;

    @Option(names = {"-n", "--last"}, paramLabel = "<N>",
            description = "When auto-discovering, use the latest N runs (default: 2).")
    int last = 2;

    @Option(names = "--results-root", paramLabel = "<dir>",
            description = "Where to search for runs when none are given (default: ./results).")
    Path resultsRoot = Path.of("results");

    @Override
    public Integer call() {
        var runs = dirs.isEmpty() ? discover(resultsRoot, last) : dirs.stream().map(Path::normalize).toList();
        if (runs.isEmpty()) {
            System.err.println(Ansi.fail("report: no run directories given and none found under " + resultsRoot));
            return 2;
        }
        for (var d : runs) {
            if (!Files.isDirectory(d)) {
                System.err.println(Ansi.fail("report: not a directory: " + d));
                return 2;
            }
        }

        if (runs.size() == 1) {
            var dir = runs.getFirst();
            var target = out != null ? out : dir.resolve("report.html");
            var written = HtmlReport.writeSingle(dir, target);
            if (written == null) {
                System.err.println(Ansi.fail("report: no parseable results in " + dir));
                return 1;
            }
            System.out.println(Ansi.ok("report " + Ansi.ARROW + " " + Ansi.cyan(written.toString())));
            return 0;
        }

        var target = out != null ? out : Path.of("report-compare.html");
        System.out.println(Ansi.dim("report: comparing " + runs.size() + " runs"));
        runs.forEach(d -> System.out.println("  " + Ansi.dim(Ansi.BULLET + " " + d)));
        var written = HtmlReport.writeCompare(runs, target);
        System.out.println(Ansi.ok("report " + Ansi.ARROW + " " + Ansi.cyan(written.toString())));
        return 0;
    }

    /// Find the latest `n` run dirs (those containing a run-manifest.json) under `root`,
    /// returned oldest→newest so the comparison reads "vs run 1".
    static List<Path> discover(Path root, int n) {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.walk(root, 4)) {
            var found = s.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("run-manifest.json")))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(Math.max(1, n))
                    .toList();
            var asc = new ArrayList<>(found);
            asc.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return asc;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
