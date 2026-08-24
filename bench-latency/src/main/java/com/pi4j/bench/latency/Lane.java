package com.pi4j.bench.latency;

/// The version-specific half of the Block C latency harness. One implementation per
/// Pi4J flavor is compiled into `src/v${v}/java` and selected at build time by
/// `-Pv=3|4` (see `bench-latency/build.gradle.kts`), so the runner links against
/// exactly one and the V3/V4 `pi4j-core` never share a classpath (Appendix A trap).
///
/// A lane owns its Pi4J context and a single input pin on the mock chip, and exposes
/// the debugfs coordinates the [com.pi4j.bench.common.GpioMockStimulus] must drive to
/// raise an edge on that exact pin. The listener registered in [#onEdge] runs the
/// supplied callback for every rising/falling edge — the callback captures `t1` and
/// the runner subtracts the stimulus `t0`.
public interface Lane extends AutoCloseable {

    /// Stable label for result files/charts, e.g. `v4-ffm` or `v3-gpiod`.
    String name();

    /// Mock chip label whose debugfs dir carries the line node — `accessible` for the
    /// V4 setup, `pinctrl-mock` for the V3 gpiod setup (see the `gpio-setup.sh` of the
    /// matching module).
    String debugfsLabel();

    /// Line offset of the input pin within the mock chip; the same offset addressed
    /// through Pi4J when the pin was created.
    int lineOffset();

    /// Builds the Pi4J context and creates the input pin (debounce 0 — we want the raw
    /// edge, not a filtered one). Throws with a clear cause if the provider or mock
    /// chip is missing.
    void open() throws Exception;

    /// Registers an edge listener that invokes `callback` on every state change. The
    /// callback must be fast (it captures a timestamp and hands off); it runs on the
    /// lane's event-dispatch thread.
    void onEdge(Runnable callback);

    @Override
    void close() throws Exception;
}
