package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalOutputProvider;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalOutputProviderImpl;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/// V3 GPIO-output hot path — **gpiod lane only** (JNA→libgpiod). Isolated from the
/// linuxfs lane ([[LinuxFsOutputPerformanceTest]]); selects the mock chip by name.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class GpioDOutputPerformanceTest extends BaseSetup {

    private Context gpioD;
    private DigitalOutput gpioDPin;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException, IOException {
        setup("gpio");

        MockBoard.forceRaspberryPiForMock(); // let GpioDContext.initialize() proceed on amd64
        this.gpioD = Pi4J.newContextBuilder()
            .add(new GpioDDigitalOutputProviderImpl())
            .setGpioChipName("gpiochip0")
            .build();
        MockBoard.requireProvider(gpioD, DigitalOutputProvider.class,
            "The gpiod lane needs the mock gpiochip (bench-v3 gpio-setup.sh) and the local libgpiod native (-Dpi4j.library.path).");
        this.gpioDPin = gpioD.create(DigitalOutputConfigBuilder.newInstance(gpioD).address(5).build());
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException, IOException {
        gpioD.shutdown();
        tearDown("gpio");
    }

    @Benchmark
    public void testGpioDOutputRoundTrip(Blackhole blackhole) {
        gpioDPin.state(DigitalState.HIGH);
        gpioDPin.state(DigitalState.LOW);
        blackhole.consume(gpioDPin.state());
    }
}
