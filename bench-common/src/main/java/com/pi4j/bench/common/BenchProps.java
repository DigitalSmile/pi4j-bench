package com.pi4j.bench.common;

/// Reads peripheral addressing from `-Dbench.*` system properties, falling back to the
/// compiled default (the mock wiring). The orchestrator sets these from `bench.config` and
/// passes them to every forked lane (JMH via `-jvmArgsAppend`, custom runners directly), so
/// one config file re-points all lanes at a different mock layout or real hardware without
/// recompiling. With no property set, each lane behaves exactly as before.
public final class BenchProps {

    private BenchProps() {}

    /// Decimal int property, e.g. `bench.i2c.bus` → 99.
    public static int intProp(String key, int def) {
        var v = System.getProperty(key);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
    }

    /// Int property accepting hex (`0x1C`), decimal, or octal, e.g. `bench.i2c.device`.
    public static int hexProp(String key, int def) {
        var v = System.getProperty(key);
        return (v == null || v.isBlank()) ? def : Integer.decode(v.trim());
    }

    /// String property, e.g. `bench.gpiod.chip` → "gpiochip0".
    public static String strProp(String key, String def) {
        var v = System.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }
}
