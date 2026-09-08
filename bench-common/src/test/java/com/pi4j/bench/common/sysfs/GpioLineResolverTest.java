package com.pi4j.bench.common.sysfs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpioLineResolverTest {

    private static final String LABEL = GpioLineResolver.DEFAULT_LABEL;

    @TempDir
    Path root;

    @Test
    @DisplayName("resolves base + offset on the labelled chip")
    void resolvesLineOnLabelledChip() throws IOException {
        var sysfs = new FakeSysfsGpio(root)
                .chip("13040000.gpio", 0, 64)
                .chip(LABEL, 512, 9)
                .sysfs();

        var line = GpioLineResolver.resolve(sysfs, LABEL, 0);

        assertEquals(512, line.pin());
        assertEquals(0, line.offset());
        assertEquals(LABEL, line.chip().label());
    }

    @Test
    @DisplayName("refuses a line the kernel is holding instead of letting export fail with EBUSY")
    void refusesKernelHeldLine() throws IOException {
        // The exact Milk-V Mars failure: offset 2 is hogged, so root never exported gpio514
        // and Pi4J's own export returned 'Device or resource busy'.
        var sysfs = new FakeSysfsGpio(root).chip(LABEL, 512, 9, 0, 1, 3, 4, 5, 6, 7, 8).sysfs();

        var error = assertThrows(IllegalStateException.class, () -> GpioLineResolver.resolve(sysfs, LABEL, 2));

        assertTrue(error.getMessage().contains("gpio514"), error.getMessage());
        assertTrue(error.getMessage().contains("not exported"), error.getMessage());
        assertTrue(error.getMessage().contains("bench.linuxfs.offset=0"), error.getMessage());
    }

    @Test
    @DisplayName("rejects an offset past the end of the chip rather than addressing the next one")
    void rejectsOffsetPastEndOfChip() throws IOException {
        var sysfs = new FakeSysfsGpio(root)
                .chip(LABEL, 512, 9)
                .chip("inaccessible", 521, 1)
                .sysfs();

        var error = assertThrows(IllegalStateException.class, () -> GpioLineResolver.resolve(sysfs, LABEL, 9));

        assertTrue(error.getMessage().contains("outside"), error.getMessage());
        assertTrue(error.getMessage().contains("9 line(s)"), error.getMessage());
    }

    @Test
    @DisplayName("falls back to a sole 'mock'-ish label when the exact one is absent")
    void fallsBackToSoleMockLabel() throws IOException {
        var sysfs = new FakeSysfsGpio(root).chip("gpio-mock", 512, 9).sysfs();

        var line = GpioLineResolver.resolve(sysfs, LABEL, 1);

        assertEquals(513, line.pin());
        assertTrue(line.explanation().contains("sole chip"), line.explanation());
    }

    @Test
    @DisplayName("refuses to choose when several chips look like mocks")
    void refusesAmbiguousMockLabels() throws IOException {
        var sysfs = new FakeSysfsGpio(root)
                .chip("gpio-mock", 512, 9)
                .chip("other-mock", 521, 9)
                .sysfs();

        var error = assertThrows(IllegalStateException.class, () -> GpioLineResolver.resolve(sysfs, LABEL, 0));

        assertTrue(error.getMessage().contains("ambiguous"), error.getMessage());
        assertTrue(error.getMessage().contains(GpioLineResolver.LABEL_PROPERTY), error.getMessage());
    }

    @Test
    @DisplayName("reports the board's real chips when the mock never loaded")
    void reportsInventoryWhenMockAbsent() throws IOException {
        var sysfs = new FakeSysfsGpio(root).chip("13040000.gpio", 0, 64).sysfs();

        var error = assertThrows(IllegalStateException.class, () -> GpioLineResolver.resolve(sysfs, LABEL, 0));

        assertTrue(error.getMessage().contains("13040000.gpio"), error.getMessage());
        assertTrue(error.getMessage().contains("gpio-linuxfs-setup.sh"), error.getMessage());
    }

    @Test
    @DisplayName("a negative offset is a configuration error")
    void rejectsNegativeOffset() throws IOException {
        var sysfs = new FakeSysfsGpio(root).chip(LABEL, 512, 9).sysfs();

        assertThrows(IllegalArgumentException.class, () -> GpioLineResolver.resolve(sysfs, LABEL, -1));
    }
}
