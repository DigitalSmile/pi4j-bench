package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputConfigBuilder;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.linuxfs.provider.gpio.digital.LinuxFsDigitalInputProviderImpl;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/// V3 GPIO-input hot path — **linuxfs lane only** (pure-Java sysfs). Isolated from the
/// gpiod lane ([[GpioDInputPerformanceTest]]) so the two never share a context or the
/// GpioDContext singleton.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LinuxFsInputPerformanceTest extends BaseSetup {

    private Context linuxFs;
    private DigitalInput linuxFsPin;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException, IOException {
        setup("gpio-linuxfs"); // IRQ-disabled mock + pre-exported/chmod'd sysfs pins
        this.linuxFs = Pi4J.newContextBuilder()
            .add(new LinuxFsDigitalInputProviderImpl("/sys/class/gpio/"))
            .build();
        this.linuxFsPin = linuxFs.create(DigitalInputConfigBuilder.newInstance(linuxFs)
            .address(LinuxFsMock.base()) // discovered base + offset 0
            .debounce(99L, TimeUnit.MICROSECONDS)
            .pull(PullResistance.PULL_DOWN)
            .build());
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException, IOException {
        linuxFs.shutdown();
        tearDown("gpio-linuxfs");
    }

    @Benchmark
    public void testLinuxFsInputRoundTrip(Blackhole blackhole) {
        blackhole.consume(linuxFsPin.state());
    }
}
