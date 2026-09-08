package com.pi4j.bench.common.sysfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/// Read-only view over the legacy `/sys/class/gpio` interface: which chips exist, what they
/// are labelled, and which lines are currently exported.
///
/// Exported-ness matters because it is the difference between a clean run and a late
/// failure. `gpio-linuxfs-setup.sh` pre-exports every line of the mock chip as root so the
/// non-root JMH JVM can write `direction`/`value`; Pi4J's `LinuxFsDigitalInput.initialize()`
/// exports a line only when `/sys/class/gpio/gpioN` is absent. So a line that is *not*
/// exported after setup is one the kernel refused to hand over — a hogged/claimed line —
/// and Pi4J will rediscover that as `EBUSY` several frames deep. Checking here turns that
/// into an early, explainable failure. See `docs/adr/0002-linuxfs-gpio-line-validation.md`.
///
/// The root is injectable so discovery is unit-testable against a fake tree.
public final class SysfsGpio {

    /// The real legacy sysfs GPIO directory.
    public static final Path SYS_CLASS_GPIO = Path.of("/sys/class/gpio");

    private static final Pattern CHIP_NODE = Pattern.compile("gpiochip\\d+");
    private static final String LABEL_ATTRIBUTE = "label";
    private static final String BASE_ATTRIBUTE = "base";
    private static final String NGPIO_ATTRIBUTE = "ngpio";
    private static final String PIN_PREFIX = "gpio";

    private final Path root;

    /// @param root the directory holding `gpiochipN` and exported `gpioN` entries
    ///             (normally [#SYS_CLASS_GPIO])
    public SysfsGpio(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /// A view over the running kernel's `/sys/class/gpio`.
    public static SysfsGpio ofSystem() {
        return new SysfsGpio(SYS_CLASS_GPIO);
    }

    /// The sysfs root this instance reads.
    public Path root() {
        return root;
    }

    /// Every readable `gpiochipN`, ordered by base. Empty when the root is absent (a kernel
    /// built without `CONFIG_GPIO_SYSFS`, or a non-Linux host).
    public List<SysfsGpioChip> chips() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            return entries.map(SysfsGpio::toChip)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingInt(SysfsGpioChip::base))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + root, e);
        }
    }

    /// The chip whose `label` is exactly `label`. Exact rather than "contains" so that adding
    /// a second mock chip, or a board whose own controller happens to share a substring,
    /// cannot change which chip a lane measures.
    public Optional<SysfsGpioChip> chipLabelled(String label) {
        Objects.requireNonNull(label, "label");
        return chips().stream().filter(chip -> chip.label().equals(label)).findFirst();
    }

    /// Every chip whose label contains `fragment` — the compatibility path for setups whose
    /// labels predate the exact-label property.
    public List<SysfsGpioChip> chipsLabelledLike(String fragment) {
        Objects.requireNonNull(fragment, "fragment");
        return chips().stream().filter(chip -> chip.label().contains(fragment)).toList();
    }

    /// The chip owning global line `pin`, if any.
    public Optional<SysfsGpioChip> chipOwning(int pin) {
        return chips().stream().filter(chip -> chip.owns(pin)).findFirst();
    }

    /// Whether `/sys/class/gpio/gpio<pin>` exists, i.e. the line is exported to userspace.
    public boolean isExported(int pin) {
        return Files.isDirectory(root.resolve(PIN_PREFIX + pin));
    }

    /// The chip-relative offsets of `chip` that are currently exported — the lines a lane can
    /// actually use. A gap in this list is a line the kernel is holding.
    public List<Integer> exportedOffsets(SysfsGpioChip chip) {
        Objects.requireNonNull(chip, "chip");
        return IntStream.range(0, chip.ngpio())
                .filter(offset -> isExported(chip.pin(offset)))
                .boxed()
                .toList();
    }

    /// Multi-line dump of every chip and its exported offsets, for failure messages. Without
    /// it a GPIO number in an exception is unactionable — this says which chip owns it.
    public String inventory() {
        var chips = chips();
        if (chips.isEmpty()) {
            return "  (no gpiochip under " + root + ")";
        }
        return chips.stream()
                .map(chip -> "  " + chip.describe() + ", exported offsets " + exportedOffsets(chip))
                .collect(Collectors.joining("\n"));
    }

    private static Optional<SysfsGpioChip> toChip(Path entry) {
        var node = entry.getFileName().toString();
        if (!CHIP_NODE.matcher(node).matches()) {
            return Optional.empty();
        }
        var label = readString(entry.resolve(LABEL_ATTRIBUTE));
        var base = readInt(entry.resolve(BASE_ATTRIBUTE));
        var ngpio = readInt(entry.resolve(NGPIO_ATTRIBUTE));
        if (label.isEmpty() || base.isEmpty() || ngpio.isEmpty()) {
            return Optional.empty(); // a chip we cannot address is not worth reporting
        }
        return Optional.of(new SysfsGpioChip(node, label.get(), base.getAsInt(), ngpio.getAsInt(), entry));
    }

    private static Optional<String> readString(Path attribute) {
        try {
            return Optional.of(Files.readString(attribute).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static OptionalInt readInt(Path attribute) {
        return readString(attribute).map(text -> {
            try {
                return OptionalInt.of(Integer.parseInt(text));
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }).orElseGet(OptionalInt::empty);
    }
}
