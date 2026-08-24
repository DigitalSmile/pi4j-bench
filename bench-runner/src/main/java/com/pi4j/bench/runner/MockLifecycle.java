package com.pi4j.bench.runner;

import com.pi4j.bench.common.Ansi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// The shared mock kernel drivers for the custom Block C/D runners (ports the old
/// `loadMocks`/`unloadMocks` tasks). The JMH lanes self-load their mocks per trial via
/// `BaseSetup`; these are the gpio ('accessible' chip → latency V4) and i2c (→ memory)
/// mocks the LatencyRunner/MemoryRunner need pre-loaded. Load is fail-loud (the setup
/// scripts return 0 even when insmod misses, so we verify the node appeared); unload is
/// best-effort so a teardown hiccup never masks a real result.
final class MockLifecycle {

    // The setup scripts use paths relative to their own dir, so they must run FROM
    // resources/ (exactly as BaseSetup does) — else `../native/…` and `insmod <iface>-mock.ko`
    // silently miss.
    private final Path resources;
    private final Config cfg;
    private final List<String> ifaces;
    private final Map<String, String> expectedNode;

    /// `resources` holds the `<iface>-setup.sh`/`-clean.sh` scripts — in a dev build that's
    /// bench-v4/src/test/resources; in a bundle it's the staged jmh/v4/src/test/resources.
    /// The interfaces to load and the node that proves each one is up come from [RunConfig].
    MockLifecycle(Path resources, Config cfg, RunConfig runConfig) {
        this.resources = resources;
        this.cfg = cfg;
        this.ifaces = runConfig.ifaces();
        this.expectedNode = runConfig.nodes();
    }

    void load() {
        if (!cfg.isMock()) return;
        for (var iface : ifaces) {
            var script = resources.resolve(iface + "-setup.sh").toAbsolutePath().toString();
            if (cfg.dryRun()) {
                System.out.println("  [dry-run] sudo " + script);
                continue;
            }
            var r = Sh.capture(List.of("sudo", script), resources);
            if (r.exit() != 0) {
                throw new IllegalStateException("loadMocks: " + iface + "-setup.sh failed (need NOPASSWD "
                        + "sudoers for " + resources + "/):\n" + r.out());
            }
            var expected = expectedNode.get(iface);
            if (expected != null && !Files.exists(Path.of(expected))) {
                throw new IllegalStateException("loadMocks: " + iface + "-setup.sh ran but " + expected
                        + " did not appear — the mock isn't loaded. Check dmesg. Script output:\n" + r.out());
            }
            System.out.println("  " + Ansi.ok(Ansi.value(iface) + " mock loaded"
                    + (expected != null ? Ansi.dim(" (" + expected + ")") : "")));
        }
    }

    void unload() {
        if (!cfg.isMock()) return;
        for (var iface : ifaces) {
            var script = resources.resolve(iface + "-clean.sh").toAbsolutePath().toString();
            if (cfg.dryRun()) {
                System.out.println("  [dry-run] sudo " + script);
                continue;
            }
            var r = Sh.capture(List.of("sudo", script), resources);
            System.out.println("  " + (r.exit() == 0
                    ? Ansi.ok(Ansi.value(iface) + " mock unloaded")
                    : Ansi.warn(iface + " unload best-effort (rc=" + r.exit() + ")")));
        }
    }
}
