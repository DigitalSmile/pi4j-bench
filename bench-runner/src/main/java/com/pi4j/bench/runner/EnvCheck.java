package com.pi4j.bench.runner;

import com.pi4j.bench.common.Ansi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/// Read-only environment gate (ports the old root `envCheck` task). Verifies the machine
/// is quiet enough for defensible numbers and that the V3 native toolchain will resolve,
/// failing fast *before* 10+ minutes of JMH. VM-graceful: missing cpufreq/turbo degrade
/// to a warning rather than aborting.
final class EnvCheck {

    private EnvCheck() {}

    static void run(Config cfg) {
        var problems = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        // CPU governor — performance for stable numbers; absent on VMs (degrade to warn).
        var gov = Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor");
        if (!Files.exists(gov)) {
            warnings.add("no cpufreq (VM/container?) — governor pinning unavailable; numbers noisier");
        } else {
            var g = readTrim(gov);
            if (!g.equals("performance")) {
                warnings.add("CPU governor is '" + g + "', not 'performance' — run: sudo tools/pin-env.sh");
            }
        }

        // taskset — CPU pinning of the forked measurement JVMs is mandatory for slides.
        if (Sh.capture("taskset", "--version").exit() != 0) {
            problems.add("taskset not found (install util-linux) — CPU pinning unavailable");
        }

        // libgpiod must be the v1 API (the V3 gpiod JNI wrapper targets v1; v2 breaks it).
        var gpiod = Sh.capture("pkg-config", "--modversion", "libgpiod");
        if (gpiod.exit() != 0) {
            warnings.add("libgpiod not found via pkg-config — the V3 gpiod lane (native-v3) will fail to build");
        } else if (!gpiod.out().startsWith("1.")) {
            problems.add("libgpiod " + gpiod.out() + " is not the v1.x API the V3 wrapper needs — "
                    + "pin the distro package (plan Appendix A)");
        } else {
            System.out.println("  " + Ansi.ok("libgpiod " + Ansi.value(gpiod.out()) + " " + Ansi.dim("(v1 API)")));
        }

        // sudoers — the mock setup/clean scripts insmod via NOPASSWD sudo. The exact line
        // (with the resolved script dir) is printed after mocks are materialised.
        if (cfg.isMock()) {
            warnings.add("mock lane insmods via sudo — see the exact NOPASSWD sudoers line printed below");
        }

        warnings.forEach(w -> System.out.println("  " + Ansi.warn(w)));
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.out.println("  " + Ansi.fail(p)));
            throw new IllegalStateException("envCheck failed ("
                    + problems.size() + " problem(s)) — see above");
        }
        System.out.printf("  %s  %s%n",
                Ansi.ok("environment OK for " + Ansi.value(cfg.lane()) + " on " + Ansi.value(System.getProperty("os.arch"))),
                Ansi.dim(warnings.size() + " warning(s)"));
    }

    private static String readTrim(Path p) {
        try {
            return Files.readString(p).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
