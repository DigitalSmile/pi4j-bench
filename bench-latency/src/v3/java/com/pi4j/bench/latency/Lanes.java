package com.pi4j.bench.latency;

/// Flavor-selected lane factory. Only `src/v3/java` is on the source path when the
/// build resolves `-Pv=3`, so the shared [LatencyRunner] links against this one and
/// never sees the V4 classes. The V4 counterpart lives in `src/v4/java`.
final class Lanes {

    private Lanes() {
    }

    static Lane create() {
        return new V3Lane();
    }
}
