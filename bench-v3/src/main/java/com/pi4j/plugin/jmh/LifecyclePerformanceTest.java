package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalOutputProvider;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalOutputProviderImpl;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/// Block B — lifecycle (§6.2), **gpiod (JNA→libgpiod) lane**. Mirror of bench-v4's
/// [[LifecyclePerformanceTest]] (same method names → merged charts line up): builds and
/// shuts a Pi4J context down *inside* each `@Benchmark` to time init/teardown, in ms/op.
/// This is where V4's headline "init costs more" honestly shows up — JNA + reflection
/// provider wiring vs the FFM path — so the slide-28 number is measured, not asserted.
///
///  - `testCreateShutdown`  — explicit gpiod provider + `create()` a pin + `shutdown()`.
///  - `testExplicitContext` — `newContextBuilder().add(provider).setGpioChipName(..).build()` + `shutdown()`.
///  - `testAutoContext`     — `Pi4J.newAutoContext()` + `shutdown()` (loads every V3 plugin on the classpath;
///                            pigpio init fails+is swallowed on amd64 — part of the honest discovery cost).
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LifecyclePerformanceTest extends BaseSetup {

    private String chip;
    private int address;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException, IOException {
        setup("gpio");
        // GpioDContext.initialize() bails on non-Pi hosts; force a model once so every
        // in-benchmark context build proceeds against the mock chip (see MockBoard).
        MockBoard.forceRaspberryPiForMock();
        this.chip = com.pi4j.bench.common.BenchProps.strProp("bench.gpiod.chip", "gpiochip0");
        this.address = com.pi4j.bench.common.BenchProps.intProp("bench.gpiod.out.address", 5);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException, IOException {
        tearDown("gpio");
    }

    @Benchmark
    public void testCreateShutdown(Blackhole blackhole) {
        Context gpioD = Pi4J.newContextBuilder()
            .add(new GpioDDigitalOutputProviderImpl())
            .setGpioChipName(chip)
            .build();
        MockBoard.requireProvider(gpioD, DigitalOutputProvider.class,
            "The gpiod lifecycle lane needs the mock gpiochip (gpio-setup.sh) and the local libgpiod native (-Dpi4j.library.path).");
        DigitalOutput pin = gpioD.create(DigitalOutputConfigBuilder.newInstance(gpioD).address(address).build());
        blackhole.consume(pin);
        gpioD.shutdown();
    }

    @Benchmark
    public void testExplicitContext(Blackhole blackhole) {
        Context gpioD = Pi4J.newContextBuilder()
            .add(new GpioDDigitalOutputProviderImpl())
            .setGpioChipName(chip)
            .build();
        blackhole.consume(gpioD);
        gpioD.shutdown();
    }

    @Benchmark
    public void testAutoContext(Blackhole blackhole) {
        Context gpioD = Pi4J.newAutoContext();
        blackhole.consume(gpioD);
        gpioD.shutdown();
    }
}
