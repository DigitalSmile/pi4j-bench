package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalInputProvider;
import com.pi4j.io.gpio.digital.DigitalStateChangeEvent;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalInputProviderImpl;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/// V3 GPIO-input hot path — **gpiod lane only** (JNA→libgpiod). Kept in its own class
/// so it never shares a context/mock lifecycle with the linuxfs lane
/// ([[LinuxFsInputPerformanceTest]]). Uses the 3.0.2 API: `newInstance(context)` +
/// `address()`, and selects the mock chip deterministically by name (`gpiochip0`)
/// instead of relying on the "pinctrl" label heuristic.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class GpioDInputPerformanceTest extends BaseSetup {

    private Context gpioD;
    private DigitalInput gpioDPin;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException, IOException {
        setup("gpio");

        MockBoard.forceRaspberryPiForMock(); // let GpioDContext.initialize() proceed on amd64
        this.gpioD = Pi4J.newContextBuilder()
            .add(new GpioDDigitalInputProviderImpl())
            .setGpioChipName("gpiochip0")
            .build();
        MockBoard.requireProvider(gpioD, DigitalInputProvider.class,
            "The gpiod lane needs the mock gpiochip (bench-v3 gpio-setup.sh) and the local libgpiod native (-Dpi4j.library.path).");
        this.gpioDPin = gpioD.create(DigitalInputConfigBuilder.newInstance(gpioD)
            .address(3)
            .debounce(99L, TimeUnit.MICROSECONDS)
            .pull(PullResistance.PULL_DOWN)
            .build());
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException, IOException {
        gpioD.shutdown();
        tearDown("gpio");
    }

    @Benchmark
    public void testGpioDInputRoundTrip(Blackhole blackhole) {
        blackhole.consume(gpioDPin.state());
    }

    @Benchmark
    public void testGpioDInputWithListenerRoundTrip(Blackhole blackhole) {
        gpioDPin.addListener((DigitalStateChangeEvent event) -> { });
        blackhole.consume(gpioDPin.state());
        gpioDPin.removeListener();
    }
}
