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

class SysfsGpioTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("orders chips by base and reads their attributes")
    void readsChipsInBaseOrder() throws IOException {
        var sysfs = new FakeSysfsGpio(root)
                .chip("linuxfs-mock", 512, 9)
                .chip("inaccessible", 521, 1)
                .sysfs();

        assertEquals(List.of(512, 521), sysfs.chips().stream().map(SysfsGpioChip::base).toList());
        assertEquals("linuxfs-mock", sysfs.chips().getFirst().label());
        assertEquals(520, sysfs.chips().getFirst().lastPin());
    }

    @Test
    @DisplayName("matches a chip by exact label, not by substring")
    void matchesExactLabel() throws IOException {
        var sysfs = new FakeSysfsGpio(root)
                .chip("linuxfs-mock", 512, 9)
                .chip("gpio-mock", 521, 4)
                .sysfs();

        assertEquals(512, sysfs.chipLabelled("linuxfs-mock").orElseThrow().base());
        assertEquals(521, sysfs.chipLabelled("gpio-mock").orElseThrow().base());
        assertFalse(sysfs.chipLabelled("mock").isPresent());
        assertEquals(2, sysfs.chipsLabelledLike("mock").size());
    }

    @Test
    @DisplayName("a line the kernel held is simply a gap in the exported offsets")
    void reportsGapWhereExportFailed() throws IOException {
        // hog_lines=2: the mock claims line 2, so root's pre-export of it fails.
        var sysfs = new FakeSysfsGpio(root).chip("linuxfs-mock", 512, 9, 0, 1, 3, 4, 5, 6, 7, 8).sysfs();
        var chip = sysfs.chips().getFirst();

        assertEquals(List.of(0, 1, 3, 4, 5, 6, 7, 8), sysfs.exportedOffsets(chip));
        assertTrue(sysfs.isExported(512));
        assertFalse(sysfs.isExported(514));
    }

    @Test
    @DisplayName("finds which chip owns a global line number")
    void findsOwningChip() throws IOException {
        var sysfs = new FakeSysfsGpio(root)
                .chip("linuxfs-mock", 512, 9)
                .chip("inaccessible", 521, 1)
                .sysfs();

        assertEquals("linuxfs-mock", sysfs.chipOwning(520).orElseThrow().label());
        assertEquals("inaccessible", sysfs.chipOwning(521).orElseThrow().label());
        assertFalse(sysfs.chipOwning(522).isPresent());
    }

    @Test
    @DisplayName("skips chips whose attributes cannot be read")
    void skipsUnreadableChips() throws IOException {
        var sysfs = new FakeSysfsGpio(root).chip("linuxfs-mock", 512, 9).unreadableChip(600).sysfs();

        assertEquals(1, sysfs.chips().size());
    }

    @Test
    @DisplayName("an absent /sys/class/gpio yields no chips rather than an error")
    void missingRootIsEmpty() {
        var sysfs = new SysfsGpio(root.resolve("does-not-exist"));

        assertTrue(sysfs.chips().isEmpty());
        assertTrue(sysfs.inventory().contains("no gpiochip"));
    }

    @Test
    @DisplayName("the inventory names each chip and its usable offsets")
    void inventoryIsActionable() throws IOException {
        var sysfs = new FakeSysfsGpio(root).chip("linuxfs-mock", 512, 9, 0, 1, 3).sysfs();

        var inventory = sysfs.inventory();
        assertTrue(inventory.contains("linuxfs-mock"), inventory);
        assertTrue(inventory.contains("512-520"), inventory);
        assertTrue(inventory.contains("[0, 1, 3]"), inventory);
    }
}
