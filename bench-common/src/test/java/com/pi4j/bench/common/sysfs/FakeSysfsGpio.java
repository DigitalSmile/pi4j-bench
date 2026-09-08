package com.pi4j.bench.common.sysfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Builds a throwaway `/sys/class/gpio` tree: `gpiochipN/{label,base,ngpio}` plus the
/// `gpioN` directories that exist for exported lines only.
///
/// `chip(...)` takes the offsets to export rather than exporting everything, so a test can
/// reproduce the shape that matters — a hogged line the kernel refused to release, which is
/// simply a gap in the exported set.
final class FakeSysfsGpio {

    private final Path root;

    FakeSysfsGpio(Path root) {
        this.root = root;
    }

    /// A chip with every line exported.
    FakeSysfsGpio chip(String label, int base, int ngpio) throws IOException {
        var all = new int[ngpio];
        for (var i = 0; i < ngpio; i++) {
            all[i] = i;
        }
        return chip(label, base, ngpio, all);
    }

    /// A chip where only `exportedOffsets` were successfully exported.
    FakeSysfsGpio chip(String label, int base, int ngpio, int... exportedOffsets) throws IOException {
        var dir = Files.createDirectories(root.resolve("gpiochip" + base));
        Files.writeString(dir.resolve("label"), label + "\n");
        Files.writeString(dir.resolve("base"), base + "\n");
        Files.writeString(dir.resolve("ngpio"), ngpio + "\n");
        for (var offset : exportedOffsets) {
            Files.createDirectories(root.resolve("gpio" + (base + offset)));
        }
        return this;
    }

    /// A `gpiochipN` whose attributes are missing — unreadable chips must be skipped, not
    /// crash the scan.
    FakeSysfsGpio unreadableChip(int base) throws IOException {
        Files.createDirectories(root.resolve("gpiochip" + base));
        return this;
    }

    SysfsGpio sysfs() {
        return new SysfsGpio(root);
    }
}
