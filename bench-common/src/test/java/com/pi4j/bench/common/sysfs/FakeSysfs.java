package com.pi4j.bench.common.sysfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Builds a throwaway `/sys` tree that mirrors how the kernel lays PWM chips out, so
/// [SysfsPwm] can be exercised without a board:
///
/// ```text
/// <root>/class/pwm/pwmchip1        -> ../../devices/platform/pwm-mock.0/pwm/pwmchip1
/// <root>/devices/platform/pwm-mock.0/driver -> ../../../bus/platform/drivers/pwm-mock
/// <root>/devices/platform/pwm-mock.0/pwm/pwmchip1/{npwm,device -> ../..}
/// ```
///
/// `boundDriver` reproduces a chip whose parent platform device has a driver bound (the
/// `device/driver` link resolves); `unboundDriver` reproduces one where it does not, so the
/// canonical-path fallback is the only route to a name — both shapes occur in the wild.
final class FakeSysfs {

    private final Path root;

    FakeSysfs(Path root) {
        this.root = root;
    }

    /// A chip whose platform device has `driver` bound, discoverable via `device/driver`.
    FakeSysfs boundDriver(int index, String platformDevice, String driver, int channels) throws IOException {
        var chip = chipDir(index, platformDevice, channels);
        var driverDir = Files.createDirectories(root.resolve("bus/platform/drivers").resolve(driver));
        Files.createSymbolicLink(chip.getParent().getParent().resolve("driver"), driverDir);
        return this;
    }

    /// A chip with no bound driver — only the `/sys/devices/platform/<device>/` name exists.
    FakeSysfs unboundDriver(int index, String platformDevice, int channels) throws IOException {
        chipDir(index, platformDevice, channels);
        return this;
    }

    /// A `/sys/class/pwm` entry that is not a chip at all (the class dir also holds `uevent`).
    FakeSysfs stray(String name) throws IOException {
        Files.createFile(classDir().resolve(name));
        return this;
    }

    SysfsPwm sysfs() throws IOException {
        return new SysfsPwm(classDir());
    }

    /// Create `/sys/devices/platform/<device>/pwm/pwmchip<index>` plus its `npwm`, `device`
    /// backlink and the `/sys/class/pwm/pwmchip<index>` symlink; returns the chip directory.
    private Path chipDir(int index, String platformDevice, int channels) throws IOException {
        var device = root.resolve("devices/platform").resolve(platformDevice);
        var chip = device.resolve("pwm").resolve("pwmchip" + index);
        Files.createDirectories(chip);
        if (channels >= 0) {
            Files.writeString(chip.resolve("npwm"), channels + "\n");
        }
        Files.createSymbolicLink(chip.resolve("device"), device);
        Files.createSymbolicLink(classDir().resolve("pwmchip" + index), chip);
        return chip;
    }

    private Path classDir() throws IOException {
        return Files.createDirectories(root.resolve("class/pwm"));
    }
}
