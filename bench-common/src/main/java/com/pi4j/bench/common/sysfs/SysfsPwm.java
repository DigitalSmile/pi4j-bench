package com.pi4j.bench.common.sysfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/// Read-only view over `/sys/class/pwm`, used to find out *which* `pwmchipN` a given kernel
/// driver owns instead of assuming an index.
///
/// The PWM core numbers chips in registration order, so `pwmchip0` means nothing by itself:
/// on a Raspberry Pi with no PWM overlay the benchmark's `pwm-mock` module lands on
/// `pwmchip0`, while on a board with an on-SoC controller (StarFive JH7110 / Milk-V Mars)
/// that index belongs to real hardware and the mock is pushed to `pwmchip1`. Driving the
/// wrong chip does not fail cleanly — the SoC driver accepts `period` and `duty_cycle` and
/// then rejects `polarity` with `EINVAL`, which is how this class came to exist. See
/// `docs/adr/0001-pwm-chip-auto-discovery.md`.
///
/// The sysfs root is injectable so the discovery logic is unit-testable against a fake tree.
public final class SysfsPwm {

    /// The real sysfs PWM class directory.
    public static final Path SYS_CLASS_PWM = Path.of("/sys/class/pwm");

    private static final Pattern CHIP_NODE = Pattern.compile("pwmchip(\\d+)");

    /// Platform devices are named `<driver>.<instance>` (`pwm-mock.0`); the instance suffix is
    /// stripped so the name matches the driver directory under `/sys/bus/platform/drivers`.
    private static final Pattern INSTANCE_SUFFIX = Pattern.compile("\\.\\d+$");

    private static final String PLATFORM_SEGMENT = "platform";
    private static final String NPWM_ATTRIBUTE = "npwm";

    private final Path root;

    /// @param root the directory holding the `pwmchipN` entries (normally [#SYS_CLASS_PWM])
    public SysfsPwm(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /// A view over the running kernel's `/sys/class/pwm`.
    public static SysfsPwm ofSystem() {
        return new SysfsPwm(SYS_CLASS_PWM);
    }

    /// The sysfs root this instance reads.
    public Path root() {
        return root;
    }

    /// Every `pwmchipN` currently registered, ordered by index. Empty when the root does not
    /// exist (no PWM support compiled in, or a non-Linux host).
    public List<PwmChip> chips() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries.map(SysfsPwm::toChip)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingInt(PwmChip::index))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + root, e);
        }
    }

    /// The chip owned by `driver` (e.g. `pwm-mock`), or empty when no chip reports that driver.
    /// If several match — which the mock module never does, it registers a single chip — the
    /// lowest index wins.
    public Optional<PwmChip> chipOf(String driver) {
        Objects.requireNonNull(driver, "driver");
        return chips().stream().filter(chip -> chip.drivenBy(driver)).findFirst();
    }

    private static Optional<PwmChip> toChip(Path entry) {
        var matcher = CHIP_NODE.matcher(entry.getFileName().toString());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new PwmChip(
                Integer.parseInt(matcher.group(1)),
                driverOf(entry).orElse(PwmChip.UNKNOWN_DRIVER),
                channelsOf(entry),
                entry));
    }

    /// Identify the owning driver, in decreasing order of precision:
    ///
    ///  1. `device/driver` — the bound driver's own directory name, exact when one is bound;
    ///  2. `device` — the parent device (`pwm-mock.0`), for chips with no driver bound;
    ///  3. the segment after `platform` in the canonical path, for kernels/chips that expose
    ///     no `device` link at all.
    ///
    /// Steps 2 and 3 yield an OF-style name such as `41000000.pwm` for on-SoC controllers.
    /// That name is only ever displayed and compared against the mock's, so an imprecise
    /// result can never cause the wrong chip to be selected — only an uninformative label.
    private static Optional<String> driverOf(Path chip) {
        var device = chip.resolve("device");
        var bound = realName(device.resolve("driver"));
        if (bound.isPresent()) {
            return bound;
        }
        var parent = realName(device);
        return (parent.isPresent() ? parent : platformDeviceOf(chip)).map(SysfsPwm::stripInstanceSuffix);
    }

    /// The file name `link` resolves to, or empty if it is missing or unreadable.
    private static Optional<String> realName(Path link) {
        if (!Files.exists(link)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(link.toRealPath().getFileName()).map(Path::toString);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /// Last-resort naming: the segment right after `platform` in the chip's canonical path,
    /// `/sys/devices/platform/<device>/…/pwmchipN`. Only reached when the chip exposes no
    /// `device` link, so it may name an enclosing bus node rather than the controller.
    private static Optional<String> platformDeviceOf(Path chip) {
        try {
            var real = chip.toRealPath();
            for (var i = 0; i < real.getNameCount() - 1; i++) {
                if (PLATFORM_SEGMENT.equals(real.getName(i).toString())) {
                    return Optional.of(real.getName(i + 1).toString());
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String stripInstanceSuffix(String platformDevice) {
        return INSTANCE_SUFFIX.matcher(platformDevice).replaceFirst("");
    }

    private static int channelsOf(Path chip) {
        var npwm = chip.resolve(NPWM_ATTRIBUTE);
        if (!Files.isReadable(npwm)) {
            return PwmChip.UNKNOWN_CHANNELS;
        }
        try {
            return Integer.parseInt(Files.readString(npwm).trim());
        } catch (IOException | NumberFormatException e) {
            return PwmChip.UNKNOWN_CHANNELS;
        }
    }
}
