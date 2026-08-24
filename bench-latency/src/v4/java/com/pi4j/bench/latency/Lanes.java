package com.pi4j.bench.latency;

/// Flavor-selected lane factory. Only `src/v4/java` is on the source path when the
/// build resolves `-Pv=4`, so the shared [LatencyRunner] links against this one and
/// never sees the V3 classes. The V3 counterpart lives in `src/v3/java`.
final class Lanes {

    private Lanes() {
    }

    static Lane create() {
        return new V4Lane();
    }
}
