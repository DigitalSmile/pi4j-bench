package com.pi4j.bench.common;

/// Loads/unloads the Pi4J FFM mock kernel drivers for a given interface.
/// No-op when the run is on real hardware (`-Dlane=hw`); the setup scripts run
/// via sudoers NOPASSWD as described in the plan (§2, §5.1).
public final class MockSetup {

    private MockSetup() {}

    public static boolean isMockLane() {
        return !"hw".equalsIgnoreCase(System.getProperty("lane", "mock"));
    }

    public static void setup(String iface) {
        if (isMockLane()) {
            // TODO(Phase 0): invoke src/test/resources/<iface>-setup.sh via sudo.
            System.getLogger(MockSetup.class.getName())
                    .log(System.Logger.Level.INFO, () -> "mock setup: " + iface);
        }
    }

    public static void clean(String iface) {
        if (isMockLane()) {
            // TODO(Phase 0): invoke src/test/resources/<iface>-clean.sh via sudo.
            System.getLogger(MockSetup.class.getName())
                    .log(System.Logger.Level.INFO, () -> "mock clean: " + iface);
        }
    }
}
