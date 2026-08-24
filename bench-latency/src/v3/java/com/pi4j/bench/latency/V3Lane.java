package com.pi4j.bench.latency;

import com.pi4j.Pi4J;
import com.pi4j.boardinfo.definition.BoardModel;
import com.pi4j.boardinfo.util.BoardInfoHelper;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalInputProvider;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalInputProviderImpl;

import java.util.concurrent.TimeUnit;

/// V3 (gpiod) lane: the JNA→libgpiod edge path, run against the local native
/// (`-Dpi4j.library.path`, see `native-v3`). Mirrors the hot-path suite's gpiod
/// input — chip `gpiochip0`, `address(3)` on the `pinctrl-mock` mock chip
/// (`bench-v3/src/test/resources/gpio-setup.sh`) — with debounce disabled.
///
/// Two mock-lane guards from Appendix A are folded in here (kept in sync with
/// `bench-v3 MockBoard`): force a concrete Pi model so `GpioDContext.initialize()`
/// proceeds off real hardware, and fail fast if the provider was silently dropped.
final class V3Lane implements Lane {

    private Context gpioD;
    private DigitalInput pin;

    @Override
    public String name() {
        return "v3-gpiod";
    }

    @Override
    public String debugfsLabel() {
        return com.pi4j.bench.common.BenchProps.strProp("bench.gpio.label.gpiod", "pinctrl-mock");
    }

    @Override
    public int lineOffset() {
        return com.pi4j.bench.common.BenchProps.intProp("bench.gpiod.in.address", 3);
    }

    @Override
    public void open() {
        // On a non-Pi host GpioDContext.initialize() bails at !runningOnRaspberryPi();
        // force a pre-RP1 model through the public API so the JNA lane opens the chip.
        if (!BoardInfoHelper.runningOnRaspberryPi()) {
            BoardInfoHelper.current().setBoardModel(BoardModel.MODEL_4_B);
        }
        this.gpioD = Pi4J.newContextBuilder()
                .add(new GpioDDigitalInputProviderImpl())
                .setGpioChipName(com.pi4j.bench.common.BenchProps.strProp("bench.gpiod.chip", "gpiochip0"))
                .build();
        if (!gpioD.providers().exists(DigitalInputProvider.class)) {
            throw new IllegalStateException(
                    "gpiod DigitalInputProvider failed to initialize and was skipped by Pi4J. "
                    + "Needs the mock gpiochip (bench-v3 gpio-setup.sh) and the local libgpiod native "
                    + "(-Dpi4j.library.path). Re-run with "
                    + "-Dorg.slf4j.simpleLogger.defaultLogLevel=error to see the underlying cause.");
        }
        this.pin = gpioD.create(DigitalInputConfigBuilder.newInstance(gpioD)
                .address(com.pi4j.bench.common.BenchProps.intProp("bench.gpiod.in.address", 3))
                .debounce(0L, TimeUnit.MICROSECONDS)
                .pull(PullResistance.PULL_DOWN)
                .build());
    }

    @Override
    public void onEdge(Runnable callback) {
        pin.addListener(_ -> callback.run());
    }

    @Override
    public void close() {
        if (gpioD != null) {
            gpioD.shutdown();
        }
    }
}
