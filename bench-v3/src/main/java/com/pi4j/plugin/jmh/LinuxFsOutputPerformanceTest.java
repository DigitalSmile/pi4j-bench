package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.bench.common.BenchProps;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.linuxfs.provider.gpio.digital.LinuxFsDigitalOutputProviderImpl;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/// V3 GPIO-output hot path — **linuxfs lane only** (pure-Java sysfs). Isolated from the
/// gpiod lane ([[GpioDOutputPerformanceTest]]).
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LinuxFsOutputPerformanceTest extends BaseSetup {

    private Context linuxFs;
    private DigitalOutput linuxFsPin;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException, IOException {
        setup("gpio-linuxfs"); // IRQ-disabled mock + pre-exported/chmod'd sysfs pins

        // See LinuxFsInputPerformanceTest: chip located by label, line validated as exported.
        var line = LinuxFsMock.output();
        System.out.println("# linuxfs output line: " + line.explanation());

        this.linuxFs = Pi4J.newContextBuilder()
            .add(new LinuxFsDigitalOutputProviderImpl(BenchProps.strProp("bench.linuxfs.path", "/sys/class/gpio/")))
            .build();
        this.linuxFsPin = linuxFs.create(DigitalOutputConfigBuilder.newInstance(linuxFs)
            .address(line.pin()).build());
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException, IOException {
        linuxFs.shutdown();
        tearDown("gpio-linuxfs");
    }

    @Benchmark
    public void testLinuxFsOutputRoundTrip(Blackhole blackhole) {
        linuxFsPin.state(DigitalState.HIGH);
        linuxFsPin.state(DigitalState.LOW);
        blackhole.consume(linuxFsPin.state());
    }
}
