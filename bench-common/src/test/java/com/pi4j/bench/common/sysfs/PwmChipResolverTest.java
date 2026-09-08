package com.pi4j.bench.common.sysfs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PwmChipResolverTest {

    private static final String MOCK = PwmChipResolver.DEFAULT_MOCK_DRIVER;

    @TempDir
    Path root;

    @Test
    @DisplayName("auto picks the mock's real index, not pwmchip0, on a board with hardware PWM")
    void autoDiscoversMockOnBoardWithHardwarePwm() throws IOException {
        var sysfs = new FakeSysfs(root)
                .unboundDriver(0, "41000000.pwm", 8)
                .boundDriver(1, "pwm-mock.0", MOCK, 3)
                .sysfs();

        var resolution = PwmChipResolver.resolve(sysfs, PwmChipResolver.AUTO, MOCK);

        assertEquals(1, resolution.chip());
        assertTrue(resolution.explanation().contains("pwmchip1"), resolution.explanation());
    }

    @Test
    @DisplayName("auto still yields 0 where the mock is the only PWM chip")
    void autoKeepsChipZeroWhenMockIsAlone() throws IOException {
        var sysfs = new FakeSysfs(root).boundDriver(0, "pwm-mock.0", MOCK, 3).sysfs();

        assertEquals(0, PwmChipResolver.resolve(sysfs, PwmChipResolver.AUTO, MOCK).chip());
    }

    @Test
    @DisplayName("a numeric value is honoured verbatim, without touching sysfs")
    void explicitIndexWins() throws IOException {
        var sysfs = new FakeSysfs(root).boundDriver(1, "pwm-mock.0", MOCK, 3).sysfs();

        var resolution = PwmChipResolver.resolve(sysfs, "0", MOCK);

        assertEquals(0, resolution.chip());
        assertTrue(resolution.explanation().contains("pinned"), resolution.explanation());
    }

    @Test
    @DisplayName("null and blank fall through to auto")
    void blankMeansAuto() throws IOException {
        var sysfs = new FakeSysfs(root).boundDriver(2, "pwm-mock.0", MOCK, 3).sysfs();

        assertEquals(2, PwmChipResolver.resolve(sysfs, null, MOCK).chip());
        assertEquals(2, PwmChipResolver.resolve(sysfs, "   ", MOCK).chip());
    }

    @Test
    @DisplayName("auto refuses to guess when the mock is missing, and says which chips it saw")
    void autoFailsLoudlyWithoutTheMock() throws IOException {
        // Exactly the Milk-V Mars failure mode: insmod never took, only the SoC chip is there.
        var sysfs = new FakeSysfs(root).unboundDriver(0, "41000000.pwm", 8).sysfs();

        var error = assertThrows(IllegalStateException.class,
                () -> PwmChipResolver.resolve(sysfs, PwmChipResolver.AUTO, MOCK));

        assertTrue(error.getMessage().contains("41000000.pwm"), error.getMessage());
        assertTrue(error.getMessage().contains(PwmChipResolver.CHIP_PROPERTY), error.getMessage());
    }

    @Test
    @DisplayName("auto reports an empty sysfs distinctly from a populated one")
    void autoFailsLoudlyOnEmptySysfs() {
        var sysfs = new SysfsPwm(root.resolve("class/pwm"));

        var error = assertThrows(IllegalStateException.class,
                () -> PwmChipResolver.resolve(sysfs, PwmChipResolver.AUTO, MOCK));

        assertTrue(error.getMessage().contains("none"), error.getMessage());
    }

    @Test
    @DisplayName("a non-numeric, non-auto value is a configuration error")
    void rejectsGarbageValues() {
        var sysfs = new SysfsPwm(root);

        assertThrows(IllegalArgumentException.class, () -> PwmChipResolver.resolve(sysfs, "pwmchip1", MOCK));
        assertThrows(IllegalArgumentException.class, () -> PwmChipResolver.resolve(sysfs, "-1", MOCK));
    }
}
