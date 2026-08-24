package com.pi4j.bench.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/// Runtime configuration shipped alongside the binary and handed to it at run time
/// (`--config bench.config`, or auto-picked up from the bundle root). Externalises the two
/// things that vary per machine without recompiling:
///
///  1. **the JDK** used to fork the measurement lanes (a native image has none of its own),
///  2. the **mock name/address map** — which interfaces to load, the node whose appearance
///     proves the mock is up, and the bus/device/chip/line the lanes talk to (mock defaults,
///     or the real-hardware addresses on `--lane hw`).
///
/// Every value has a built-in default (the current mock wiring), so no config file is
/// required; a file overlays only the keys it sets. Addressing keys (`bench.*`) are passed
/// straight through to the forked MAIN lanes as `-D` system properties.
public record RunConfig(
        String jdk,
        List<String> ifaces,
        Map<String, String> nodes,
        Map<String, String> laneProps,
        RunOptions options,
        Map<String, Map<String, String>> presets) {

    /// Run-behaviour options that mirror the CLI flags, settable in bench.config under the
    /// `run.` prefix (distinct from the `bench.` addressing prefix). Every field is nullable:
    /// `null` means "not set here", so the CLI flag (if given) and then the built-in default
    /// win — precedence is CLI > config > default (resolved in [BenchRunner]).
    ///   run.gc = g1|zgc|parallel|serial|shenandoah|epsilon
    ///   run.quick = true|false        run.jfr = true|false
    ///   run.cpus = 2,3                run.profilers = gc,…
    ///   run.jhiccup = /path/jHiccup.jar
    ///   run.lane = mock|hw
    ///   run.lanes = v4-jmh,v3-jmh,…   (which lanes to run, in this order; default all)
    ///   run.leyden.jdk = /path/to/leyden-ea-jdk   (optional; the FFM-warmup Leyden variant)
    ///   run.gcs = g1,zgc,…            (GC sweep: run the whole matrix once per collector)
    ///   run.preset = <name>          (select a preset.<name>.* block)
    ///   run.gc-pressure = true|false run.gc-pressure-mbps = 200
    public record RunOptions(
            String gc, Boolean quick, String cpus, Boolean jfr,
            List<String> profilers, String jhiccup, String lane,
            List<String> lanes, String leydenJdk, Boolean nativeImage,
            List<String> gcs, String preset, Boolean gcPressure, Integer gcPressureMbps) {

        public static RunOptions empty() {
            return new RunOptions(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }
    }

    /// Built-in defaults = today's hardcoded mock wiring, no run-option overrides.
    public static RunConfig defaults() {
        return new RunConfig(
                null, // JDK: fall back to PATH / this JVM
                List.of("gpio", "i2c"),
                Map.of(
                        "gpio", "/sys/kernel/debug/gpio-mock/accessible/line3",
                        "i2c", "/dev/i2c-99"),
                defaultAddressing(),
                RunOptions.empty(),
                Map.of());
    }

    /// Mock-wiring addressing, passed to every lane as `-Dbench.*`. Each lane also holds
    /// the same value as a compiled fallback (see BenchProps), so this stays in sync.
    private static Map<String, String> defaultAddressing() {
        var m = new LinkedHashMap<String, String>();
        m.put("bench.i2c.bus", "99");
        m.put("bench.i2c.device", "0x1C");
        m.put("bench.i2c.register", "0xFF");
        m.put("bench.gpio.bus", "97");            // ffm gpio chip bus
        m.put("bench.gpio.in.bcm", "3");
        m.put("bench.gpio.out.bcm", "5");
        m.put("bench.gpio.label.ffm", "accessible");
        m.put("bench.gpiod.chip", "gpiochip0");
        m.put("bench.gpiod.in.address", "3");
        m.put("bench.gpiod.out.address", "5");
        m.put("bench.gpio.label.gpiod", "pinctrl-mock");
        m.put("bench.spi.bus", "6");              // SpiBus.BUS_6
        m.put("bench.spi.channel", "0");
        m.put("bench.pwm.chip", "0");
        m.put("bench.pwm.channel", "0");
        m.put("bench.linuxfs.path", "/sys/class/gpio/");
        m.put("bench.linuxfs.offset", "0");
        m.put("bench.linuxfs.out.offset", "1");
        return m;
    }

    /// Load `file`, overlaying its keys onto [#defaults]. Recognised keys:
    ///   jdk = /path/to/jdk-25
    ///   mock.ifaces = gpio,i2c
    ///   mock.<iface>.node = /dev/…              (per iface, load-verification node)
    ///   bench.* = …                             (addressing → forked MAIN lanes as -D)
    public static RunConfig load(Path file) {
        var base = defaults();
        var props = new Properties();
        try (var r = Files.newBufferedReader(file)) {
            props.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("reading config " + file, e);
        }

        var jdk = props.getProperty("jdk", base.jdk());
        if (jdk != null && jdk.isBlank()) jdk = null;

        var ifaces = props.containsKey("mock.ifaces")
                ? List.of(props.getProperty("mock.ifaces").split("\\s*,\\s*"))
                : base.ifaces();

        var nodes = new LinkedHashMap<>(base.nodes());
        var laneProps = new LinkedHashMap<>(base.laneProps());
        // preset.<name>.<key> = value  →  presets[name][key] = value
        var presets = new LinkedHashMap<String, Map<String, String>>();
        for (var name : props.stringPropertyNames()) {
            var value = props.getProperty(name);
            if (name.startsWith("mock.") && name.endsWith(".node")) {
                nodes.put(name.substring("mock.".length(), name.length() - ".node".length()), value);
            } else if (name.startsWith("bench.")) {
                laneProps.put(name, value);
            } else if (name.startsWith("preset.")) {
                var rest = name.substring("preset.".length());
                var dot = rest.indexOf('.');
                if (dot > 0) {
                    var pName = rest.substring(0, dot);
                    var pKey = rest.substring(dot + 1);
                    presets.computeIfAbsent(pName, k -> new LinkedHashMap<>()).put(pKey, value.trim());
                }
            }
        }

        var options = new RunOptions(
                str(props, "run.gc"),
                bool(props, "run.quick"),
                str(props, "run.cpus"),
                bool(props, "run.jfr"),
                csv(props, "run.profilers"),
                str(props, "run.jhiccup"),
                str(props, "run.lane"),
                csv(props, "run.lanes"),
                str(props, "run.leyden.jdk"),
                bool(props, "run.native"),
                csv(props, "run.gcs"),
                str(props, "run.preset"),
                bool(props, "run.gc-pressure"),
                integer(props, "run.gc-pressure-mbps"));
        var frozenPresets = new LinkedHashMap<String, Map<String, String>>();
        presets.forEach((k, v) -> frozenPresets.put(k, Map.copyOf(v)));
        return new RunConfig(jdk, ifaces, Map.copyOf(nodes), laneProps, options,
                Map.copyOf(frozenPresets));
    }

    private static Integer integer(Properties p, String key) {
        var v = str(p, key);
        return v == null ? null : Integer.valueOf(v);
    }

    private static String str(Properties p, String key) {
        var v = p.getProperty(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static Boolean bool(Properties p, String key) {
        var v = str(p, key);
        return v == null ? null : (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"));
    }

    private static List<String> csv(Properties p, String key) {
        var v = str(p, key);
        return v == null ? null : List.of(v.split("\\s*,\\s*"));
    }

    /// Resolve the config to use: explicit `--config`, else `bench.config` next to the
    /// bundle/binary, else built-in defaults.
    public static RunConfig resolve(Path explicit, Path bundleRoot) {
        if (explicit != null) return load(explicit);
        if (bundleRoot != null) {
            var inBundle = bundleRoot.resolve("bench.config");
            if (Files.isRegularFile(inBundle)) return load(inBundle);
        }
        var cwd = Path.of("bench.config");
        if (Files.isRegularFile(cwd)) return load(cwd);
        // Dev (Gradle :bench-runner:run) fallback: the CWD is the repo root and the only
        // bench.config lives with the sources — pick it up so presets work without --config.
        var devConfig = Path.of("bench-runner/src/main/resources/bench.config");
        return Files.isRegularFile(devConfig) ? load(devConfig) : defaults();
    }
}
