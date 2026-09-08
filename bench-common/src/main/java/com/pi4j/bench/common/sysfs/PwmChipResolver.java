package com.pi4j.bench.common.sysfs;

import com.pi4j.bench.common.BenchProps;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/// Turns the `bench.pwm.chip` addressing property into the chip index a PWM lane should open.
///
/// Two modes:
///  * a **number** (`bench.pwm.chip = 0`) is taken verbatim — that is the `--lane hw` case,
///    where the operator knows which controller is wired to the scope;
///  * **`auto`** (the mock default) discovers the chip owned by the mock driver via
///    [SysfsPwm], because its index depends on how many PWM controllers the board registered
///    first.
///
/// `auto` never falls back to a guess. A missing mock used to degrade into "just use
/// `pwmchip0`", which on a Milk-V Mars silently pointed the benchmark at the SoC's own PWM
/// and surfaced much later as `EINVAL` from the `polarity` write. Failing here instead keeps
/// the benchmark off real hardware nobody asked it to drive.
public final class PwmChipResolver {

    /// Addressing property naming the chip: an integer, or [#AUTO].
    public static final String CHIP_PROPERTY = "bench.pwm.chip";

    /// Addressing property naming the mock driver to look for in [#AUTO] mode.
    public static final String DRIVER_PROPERTY = "bench.pwm.mock.driver";

    /// Sentinel value of [#CHIP_PROPERTY] that selects sysfs discovery.
    public static final String AUTO = "auto";

    /// Driver name registered by `bench-v4/src/test/native/pwm/pwm-mock.c`.
    public static final String DEFAULT_MOCK_DRIVER = "pwm-mock";

    private PwmChipResolver() {}

    /// The resolved chip plus a human-readable account of how it was chosen, for the lane to
    /// print alongside its results — a benchmark number is worthless without knowing which
    /// device produced it.
    ///
    /// @param chip        index to pass to `PwmConfigBuilder.chip(...)`
    /// @param explanation why this index was chosen
    public record Resolution(int chip, String explanation) {
        public Resolution {
            if (chip < 0) {
                throw new IllegalArgumentException("pwm chip index must be >= 0, got " + chip);
            }
            Objects.requireNonNull(explanation, "explanation");
        }
    }

    /// Resolve from the `bench.*` system properties the orchestrator passes to every lane.
    public static Resolution resolve(SysfsPwm sysfs) {
        return resolve(sysfs,
                BenchProps.strProp(CHIP_PROPERTY, AUTO),
                BenchProps.strProp(DRIVER_PROPERTY, DEFAULT_MOCK_DRIVER));
    }

    /// Resolve explicit values (the property-free form used by the tests).
    ///
    /// @param sysfs      the sysfs view to search in [#AUTO] mode
    /// @param requested  raw [#CHIP_PROPERTY] value; `null`/blank is treated as [#AUTO]
    /// @param mockDriver driver name to look for in [#AUTO] mode
    /// @throws IllegalArgumentException if `requested` is neither [#AUTO] nor a valid index
    /// @throws IllegalStateException    if [#AUTO] finds no chip owned by `mockDriver`
    public static Resolution resolve(SysfsPwm sysfs, String requested, String mockDriver) {
        Objects.requireNonNull(sysfs, "sysfs");
        Objects.requireNonNull(mockDriver, "mockDriver");

        var value = requested == null || requested.isBlank() ? AUTO : requested.trim();
        if (!AUTO.equalsIgnoreCase(value)) {
            return new Resolution(parseChip(value), "pinned by " + CHIP_PROPERTY + "=" + value);
        }

        var chips = sysfs.chips();
        return sysfs.chipOf(mockDriver)
                .map(chip -> new Resolution(chip.index(), "auto-discovered " + chip.describe()))
                .orElseThrow(() -> new IllegalStateException(notFound(sysfs, mockDriver, chips)));
    }

    private static int parseChip(String value) {
        int chip;
        try {
            chip = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    CHIP_PROPERTY + " must be an integer or '" + AUTO + "', got '" + value + "'", e);
        }
        if (chip < 0) {
            throw new IllegalArgumentException(CHIP_PROPERTY + " must be >= 0, got " + chip);
        }
        return chip;
    }

    private static String notFound(SysfsPwm sysfs, String mockDriver, List<PwmChip> chips) {
        var listing = chips.isEmpty()
                ? "  (none — " + sysfs.root() + " is empty or absent)"
                : chips.stream().map(chip -> "  " + chip.describe()).collect(Collectors.joining("\n"));
        return """
                no PWM chip owned by driver '%s' under %s — the mock module is not loaded.

                Chips currently registered:
                %s

                Refusing to guess an index: on a board that ships its own PWM controller
                (StarFive JH7110 / Milk-V Mars) pwmchip0 is REAL hardware, and driving it
                fails late with 'Invalid argument (22)' on the polarity write.

                Fix one of:
                  * mock lane — check that pwm-setup.sh loaded pwm-mock.ko (see dmesg);
                  * hardware lane — pin the controller explicitly, e.g. %s=0
                    (and set bench.pwm.polarity=inversed if the driver rejects 'normal').
                """.formatted(mockDriver, sysfs.root(), listing, CHIP_PROPERTY);
    }
}
