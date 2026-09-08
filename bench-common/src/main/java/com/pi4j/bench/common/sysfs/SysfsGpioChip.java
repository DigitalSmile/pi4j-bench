package com.pi4j.bench.common.sysfs;

import java.nio.file.Path;
import java.util.Objects;

/// One `/sys/class/gpio/gpiochipN` node — the legacy sysfs GPIO interface the Pi4J V3
/// linuxfs provider drives.
///
/// The sysfs interface addresses lines by a **global** number, `base + offset`, and `base`
/// is handed out by the kernel at registration time (the mock registers with `.base = -1`).
/// It therefore depends on how many GPIO controllers registered first, which differs per
/// board — so a line number is only meaningful together with the chip it came from.
///
/// @param node  directory name, e.g. `gpiochip512`
/// @param label the chip's `label` attribute (`linuxfs-mock` for the benchmark's mock chip)
/// @param base  first global line number owned by this chip
/// @param ngpio number of lines it owns
/// @param path  the `/sys/class/gpio/gpiochipN` directory
public record SysfsGpioChip(String node, String label, int base, int ngpio, Path path) {

    public SysfsGpioChip {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(path, "path");
        if (base < 0) {
            throw new IllegalArgumentException("gpiochip base must be >= 0, got " + base);
        }
        if (ngpio <= 0) {
            throw new IllegalArgumentException("gpiochip ngpio must be > 0, got " + ngpio);
        }
    }

    /// Global line number for a chip-relative `offset`, without range checking.
    public int pin(int offset) {
        return base + offset;
    }

    /// Last global line number owned by this chip.
    public int lastPin() {
        return base + ngpio - 1;
    }

    /// Whether `offset` is a line this chip actually has.
    public boolean hasOffset(int offset) {
        return offset >= 0 && offset < ngpio;
    }

    /// Whether `pin` is a global line number owned by this chip.
    public boolean owns(int pin) {
        return pin >= base && pin <= lastPin();
    }

    /// One-line rendering for diagnostics, e.g. `gpiochip512 label 'linuxfs-mock' lines 512-520`.
    public String describe() {
        return "%s label '%s' lines %d-%d".formatted(node, label, base, lastPin());
    }
}
