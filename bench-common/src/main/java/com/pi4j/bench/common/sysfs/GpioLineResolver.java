package com.pi4j.bench.common.sysfs;

import com.pi4j.bench.common.BenchProps;

import java.util.List;
import java.util.Objects;

/// Turns "the mock chip's line `offset`" into the global sysfs GPIO number a lane opens, and
/// refuses to hand back a line that is not actually usable.
///
/// Three things can go wrong between `base + offset` and a working benchmark, and all three
/// used to surface only as `Device or resource busy` from deep inside the provider:
///
///  1. **the wrong chip** — selecting by "label contains mock" over an unordered directory
///     listing is not deterministic once more than one chip matches;
///  2. **an offset past the end** of the chip, which silently addresses the next chip's lines;
///  3. **a line the kernel is holding** — the mock hogs one line on purpose (`hog_lines=2`),
///     and a board's own controller has many claimed by real drivers. `export` returns
///     `EBUSY` for those.
///
/// Each is checked here, against the chip the line actually came from, with the full sysfs
/// inventory in the failure message. See `docs/adr/0002-linuxfs-gpio-line-validation.md`.
public final class GpioLineResolver {

    /// Addressing property naming the mock chip's exact `label`.
    public static final String LABEL_PROPERTY = "bench.linuxfs.label";

    /// Label set by `gpio-linuxfs-setup.sh` on the primary (9-line) mock chip.
    public static final String DEFAULT_LABEL = "linuxfs-mock";

    /// Substring used to recognise a mock chip when [#LABEL_PROPERTY] matches nothing —
    /// the pre-existing convention, kept so older mock layouts still resolve.
    public static final String MOCK_LABEL_FRAGMENT = "mock";

    private GpioLineResolver() {}

    /// A validated sysfs GPIO line.
    ///
    /// @param pin         global line number to pass to `DigitalInput/OutputConfigBuilder.address(...)`
    /// @param offset      chip-relative offset it was resolved from
    /// @param chip        the chip that owns it
    /// @param explanation how it was chosen, for the lane to print with its results
    public record Line(int pin, int offset, SysfsGpioChip chip, String explanation) {
        public Line {
            Objects.requireNonNull(chip, "chip");
            Objects.requireNonNull(explanation, "explanation");
        }
    }

    /// Resolve `offset` on the chip named by [#LABEL_PROPERTY], reading the label from the
    /// `bench.*` system properties the orchestrator passes to every lane.
    public static Line resolve(SysfsGpio sysfs, int offset) {
        return resolve(sysfs, BenchProps.strProp(LABEL_PROPERTY, DEFAULT_LABEL), offset);
    }

    /// Resolve explicit values (the property-free form used by the tests).
    ///
    /// @throws IllegalStateException if the chip cannot be identified unambiguously, the
    ///                               offset is out of range, or the line is not exported
    public static Line resolve(SysfsGpio sysfs, String label, int offset) {
        Objects.requireNonNull(sysfs, "sysfs");
        Objects.requireNonNull(label, "label");
        if (offset < 0) {
            throw new IllegalArgumentException("gpio line offset must be >= 0, got " + offset);
        }

        var found = locate(sysfs, label);
        var chip = found.chip();

        if (!chip.hasOffset(offset)) {
            throw new IllegalStateException("""
                    line offset %d is outside %s, which has %d line(s).

                    %s

                    Addressing past the end of a chip lands on the NEXT chip's lines. Set an
                    offset below %d.
                    """.formatted(offset, chip.describe(), chip.ngpio(), sysfs.inventory(), chip.ngpio()));
        }

        var pin = chip.pin(offset);
        if (!sysfs.isExported(pin)) {
            throw new IllegalStateException(unusableLine(sysfs, chip, offset, pin));
        }

        return new Line(pin, offset, chip, "gpio%d = %s + offset %d (%s)"
                .formatted(pin, chip.node(), offset, found.how()));
    }

    /// The chip plus a note on how it was matched, so a fallback match is visible in output.
    private record Located(SysfsGpioChip chip, String how) {}

    private static Located locate(SysfsGpio sysfs, String label) {
        var exact = sysfs.chipLabelled(label);
        if (exact.isPresent()) {
            return new Located(exact.get(), "label '" + label + "'");
        }

        // Compatibility: a layout whose labels predate bench.linuxfs.label. Accepted only
        // when it is unambiguous — picking the first of several matches from an unordered
        // listing is how a lane ends up measuring a different chip on a different board.
        var like = sysfs.chipsLabelledLike(MOCK_LABEL_FRAGMENT);
        if (like.size() == 1) {
            return new Located(like.getFirst(), "sole chip labelled like '" + MOCK_LABEL_FRAGMENT + "'");
        }

        throw new IllegalStateException(noChip(sysfs, label, like));
    }

    private static String noChip(SysfsGpio sysfs, String label, List<SysfsGpioChip> ambiguous) {
        var problem = ambiguous.isEmpty()
                ? "no gpiochip is labelled '%s', and none is labelled like '%s'."
                        .formatted(label, MOCK_LABEL_FRAGMENT)
                : "no gpiochip is labelled '%s', and %d chips are labelled like '%s' — ambiguous."
                        .formatted(label, ambiguous.size(), MOCK_LABEL_FRAGMENT);
        return """
                %s

                Chips under %s:
                %s

                Fix one of:
                  * mock lane — check that gpio-linuxfs-setup.sh loaded gpio-mock.ko with
                    labels=%s,... (see dmesg);
                  * point the lane at the chip you mean with %s=<label>.
                """.formatted(problem, sysfs.root(), sysfs.inventory(), DEFAULT_LABEL, LABEL_PROPERTY);
    }

    private static String unusableLine(SysfsGpio sysfs, SysfsGpioChip chip, int offset, int pin) {
        return """
                gpio%d (%s, offset %d) is not exported.

                %s

                gpio-linuxfs-setup.sh pre-exports every line it can, so a line missing here is
                one the kernel refused to release — the mock hogs one on purpose (hog_lines),
                and a board's own controller has lines claimed by real drivers. Pi4J would
                retry the export and fail with 'Device or resource busy'.

                Pick one of the exported offsets above, e.g. bench.linuxfs.offset=%s.
                """.formatted(pin, chip.describe(), offset, sysfs.inventory(),
                        sysfs.exportedOffsets(chip).stream().findFirst()
                                .map(String::valueOf).orElse("<none available>"));
    }
}
