package com.pi4j.bench.common.sysfs;

import java.nio.file.Path;
import java.util.Objects;

/// One `/sys/class/pwm/pwmchipN` node, as discovered by [SysfsPwm].
///
/// `driver` is the kernel driver that owns the chip — `pwm-mock` for the benchmark's mock
/// module, or the SoC controller's driver/OF name on a board that ships hardware PWM (for
/// example `41000000.pwm` on the StarFive JH7110 / Milk-V Mars). It is the only reliable way
/// to tell the two apart: the chip *index* is assigned by registration order, so the mock is
/// only ever `pwmchip0` on a board whose PWM controller is absent or unbound.
///
/// @param index    the `N` in `pwmchipN`
/// @param driver   owning driver name, or [#UNKNOWN_DRIVER] when sysfs does not expose it
/// @param channels value of the chip's `npwm` attribute, or [#UNKNOWN_CHANNELS] if unreadable
/// @param path     the `/sys/class/pwm/pwmchipN` entry itself (still a symlink, not resolved)
public record PwmChip(int index, String driver, int channels, Path path) {

    /// Placeholder used when neither the `device/driver` link nor the canonical device path
    /// identifies the owning driver.
    public static final String UNKNOWN_DRIVER = "unknown";

    /// Placeholder used when `npwm` is missing or unparseable.
    public static final int UNKNOWN_CHANNELS = -1;

    public PwmChip {
        if (index < 0) {
            throw new IllegalArgumentException("pwmchip index must be >= 0, got " + index);
        }
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(path, "path");
    }

    /// The sysfs node name, e.g. `pwmchip1`.
    public String node() {
        return "pwmchip" + index;
    }

    /// Whether this chip is owned by `candidate` (exact driver-name match).
    public boolean drivenBy(String candidate) {
        return driver.equals(candidate);
    }

    /// One-line rendering for diagnostics, e.g. `pwmchip1 (driver 'pwm-mock', 3 channels)`.
    public String describe() {
        var suffix = channels == UNKNOWN_CHANNELS ? "channels unknown" : channels + " channels";
        return "%s (driver '%s', %s)".formatted(node(), driver, suffix);
    }
}
