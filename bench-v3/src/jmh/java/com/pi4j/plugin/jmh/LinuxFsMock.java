package com.pi4j.plugin.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/// The linuxfs mock's sysfs GPIO base is assigned dynamically by the kernel (the mock
/// registers with `.base = -1`), and it is NOT stable across module configurations —
/// e.g. disabling the simulated IRQ shifted it, so a hard-coded number like 511 becomes
/// `EINVAL` on export. So we discover the base at runtime from
/// `/sys/class/gpio/gpiochip*/{label,base}` (the primary mock chip's label contains
/// "mock") and address lines as `base + offset`. `gpio-linuxfs-setup.sh` has already
/// exported and chmod'd every line of that chip, so these numbers are ready to use.
final class LinuxFsMock {

    private LinuxFsMock() {}

    /// Base global GPIO number of the primary linuxfs mock chip (the 9-line one).
    static int base() {
        var classDir = Path.of("/sys/class/gpio");
        try (Stream<Path> entries = Files.list(classDir)) {
            for (var chip : (Iterable<Path>) entries
                    .filter(p -> p.getFileName().toString().startsWith("gpiochip"))::iterator) {
                var label = read(chip.resolve("label"));
                if (label != null && label.contains("mock")) {
                    var base = read(chip.resolve("base"));
                    if (base != null) return Integer.parseInt(base);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot enumerate /sys/class/gpio", e);
        }
        throw new IllegalStateException(
            "no mock gpiochip under /sys/class/gpio — is gpio-linuxfs-setup.sh loaded?");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
