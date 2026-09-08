package com.pi4j.bench.common.sysfs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysfsPwmTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("finds the mock chip behind a board's own PWM controller (Milk-V Mars layout)")
    void discoversMockBehindHardwareController() throws IOException {
        // JH7110 registers first and takes pwmchip0; pwm-mock.ko lands on pwmchip1.
        var sysfs = new FakeSysfs(root)
                .unboundDriver(0, "41000000.pwm", 8)
                .boundDriver(1, "pwm-mock.0", "pwm-mock", 3)
                .sysfs();

        var mock = sysfs.chipOf("pwm-mock").orElseThrow();

        assertEquals(1, mock.index(), "the mock is not pwmchip0 when the SoC controller registered first");
        assertEquals(3, mock.channels());
        assertEquals("pwmchip1", mock.node());
    }

    @Test
    @DisplayName("resolves the driver through the device/driver link when one is bound")
    void resolvesBoundDriver() throws IOException {
        var sysfs = new FakeSysfs(root).boundDriver(0, "pwm-mock.0", "pwm-mock", 3).sysfs();

        var chip = sysfs.chips().getFirst();

        assertEquals("pwm-mock", chip.driver());
        assertTrue(chip.drivenBy("pwm-mock"));
    }

    @Test
    @DisplayName("falls back to the platform device name when no driver is bound")
    void fallsBackToPlatformDeviceName() throws IOException {
        var sysfs = new FakeSysfs(root).unboundDriver(0, "41000000.pwm", 8).sysfs();

        assertEquals("41000000.pwm", sysfs.chips().getFirst().driver());
    }

    @Test
    @DisplayName("strips the platform instance suffix so pwm-mock.0 reads as pwm-mock")
    void stripsInstanceSuffix() throws IOException {
        var sysfs = new FakeSysfs(root).unboundDriver(2, "pwm-mock.0", 3).sysfs();

        assertEquals("pwm-mock", sysfs.chips().getFirst().driver());
    }

    @Test
    @DisplayName("names the controller, not the bus, when the device tree nests it under soc/")
    void namesNestedControllerDevice() throws IOException {
        var sysfs = new FakeSysfs(root).unboundDriver(0, "soc/13060000.pwm", 8).sysfs();

        assertEquals("13060000.pwm", sysfs.chips().getFirst().driver());
    }

    @Test
    @DisplayName("orders chips by index and ignores non-chip entries")
    void ordersChipsAndIgnoresStrayEntries() throws IOException {
        var sysfs = new FakeSysfs(root)
                .unboundDriver(10, "b.pwm", 1)
                .unboundDriver(2, "a.pwm", 1)
                .stray("uevent")
                .sysfs();

        assertEquals(List.of(2, 10), sysfs.chips().stream().map(PwmChip::index).toList());
    }

    @Test
    @DisplayName("reports unknown channels when npwm is absent")
    void unknownChannelsWithoutNpwm() throws IOException {
        var sysfs = new FakeSysfs(root).unboundDriver(0, "pwm-mock.0", -1).sysfs();

        var chip = sysfs.chips().getFirst();
        assertEquals(PwmChip.UNKNOWN_CHANNELS, chip.channels());
        assertTrue(chip.describe().contains("channels unknown"));
    }

    @Test
    @DisplayName("an absent /sys/class/pwm yields no chips rather than an error")
    void missingRootIsEmpty() {
        var sysfs = new SysfsPwm(root.resolve("does-not-exist"));

        assertTrue(sysfs.chips().isEmpty());
        assertFalse(sysfs.chipOf("pwm-mock").isPresent());
    }
}
