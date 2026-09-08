package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.bench.common.BenchProps;
import com.pi4j.bench.common.sysfs.PwmChipResolver;
import com.pi4j.bench.common.sysfs.SysfsPwm;
import com.pi4j.context.Context;
import com.pi4j.io.pwm.Pwm;
import com.pi4j.io.pwm.PwmConfigBuilder;
import com.pi4j.io.pwm.PwmPolarity;
import com.pi4j.io.pwm.PwmType;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.ffm.providers.pwm.FFMPwmProviderImpl;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class PWMPerformanceTest extends BaseSetup {

    /// Polarity written on every `on()`. The mock accepts either; some SoC drivers implement
    /// only one and reject the other with `EINVAL` (see the ADR), so `--lane hw` can override
    /// it with `-Dbench.pwm.polarity=inversed`.
    private static final String POLARITY_PROPERTY = "bench.pwm.polarity";

    private Context pi4j;
    private Pwm pwm;

    @Setup
    public void setup() throws InterruptedException, IOException {
        setup("pwm");

        // `bench.pwm.chip` defaults to `auto`: the mock module's chip index depends on how many
        // PWM controllers the board registered before it, so it is discovered from sysfs rather
        // than assumed to be 0. Printed because a PWM number means nothing without its device.
        var chip = PwmChipResolver.resolve(SysfsPwm.ofSystem());
        System.out.println("# PWM target: pwmchip" + chip.chip() + " — " + chip.explanation());

        this.pi4j = Pi4J.newContextBuilder().add(new FFMPwmProviderImpl()).build();
        var config = PwmConfigBuilder.newInstance(pi4j)
            .pwmType(PwmType.HARDWARE)
            .chip(chip.chip())
            .channel(BenchProps.intProp("bench.pwm.channel", 0))
            .polarity(PwmPolarity.parse(BenchProps.strProp(POLARITY_PROPERTY, PwmPolarity.NORMAL.getName())))
            .build();
        this.pwm = pi4j.create(config);
    }

    @TearDown
    public void tearDown() throws InterruptedException, IOException {
        pi4j.shutdown();
        tearDown("pwm");
    }

    @Benchmark
    public void testPWMRoundTrip() {
        pwm.setFrequency(500);
        pwm.setDutyCycle(5);
        pwm.on();
        pwm.off();
        pwm.on();
        pwm.setFrequency(10_000);
        pwm.setDutyCycle(10);
        pwm.off();
    }
}
