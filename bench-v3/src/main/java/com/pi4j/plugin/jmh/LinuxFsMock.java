package com.pi4j.plugin.jmh;

import com.pi4j.bench.common.BenchProps;
import com.pi4j.bench.common.sysfs.GpioLineResolver;
import com.pi4j.bench.common.sysfs.SysfsGpio;

/// Addressing for the linuxfs lane's mock GPIO chip.
///
/// The mock's sysfs base is assigned dynamically by the kernel (it registers with
/// `.base = -1`) and is not stable across module configurations or boards — disabling the
/// simulated IRQ shifted it once, and a board with its own GPIO controllers shifts it again.
/// So the chip is located by **label** and lines are addressed as `base + offset`, with the
/// resulting line validated before the provider ever touches it: see [GpioLineResolver].
///
/// `gpio-linuxfs-setup.sh` has already exported and chmod'd every line of that chip that the
/// kernel would release, so a resolved line is ready to use.
final class LinuxFsMock {

    /// Chip-relative offset of the line the input lane reads.
    static final String IN_OFFSET_PROPERTY = "bench.linuxfs.offset";

    /// Chip-relative offset of the line the output lane drives.
    static final String OUT_OFFSET_PROPERTY = "bench.linuxfs.out.offset";

    private LinuxFsMock() {}

    /// Validated global line number for the input lane (default offset 0).
    static GpioLineResolver.Line input() {
        return line(IN_OFFSET_PROPERTY, 0);
    }

    /// Validated global line number for the output lane (default offset 1).
    static GpioLineResolver.Line output() {
        return line(OUT_OFFSET_PROPERTY, 1);
    }

    private static GpioLineResolver.Line line(String offsetProperty, int defaultOffset) {
        return GpioLineResolver.resolve(SysfsGpio.ofSystem(), BenchProps.intProp(offsetProperty, defaultOffset));
    }
}
