package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputConfigBuilder;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.ffm.providers.gpio.FFMDigitalOutputProviderImpl;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/// Block B — lifecycle (§6.2). Measures the **cost of standing a Pi4J context up and
/// tearing it down** on the FFM (V4) lane, the counterpart to bench-v3's
/// [[LifecyclePerformanceTest]] (same FQCN + method names → merged charts line up).
/// Unlike the Block A hot-path tests, the context is built and shut down *inside* every
/// `@Benchmark` invocation — that IS the thing under test (reproduces the PR #458 shape).
/// ms/op, because init is orders of magnitude slower than a toggle.
///
/// Three shapes, honest "init costs more" story for slides 27–28:
///  - `testCreateShutdown`  — explicit provider + `create()` a pin + `shutdown()` (full round-trip).
///  - `testExplicitContext` — `newContextBuilder().add(provider).build()` + `shutdown()` (no pin: provider add/init only).
///  - `testAutoContext`     — `Pi4J.newAutoContext()` + `shutdown()` (ServiceLoader auto-discovery cost).
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class LifecyclePerformanceTest extends BaseSetup {

    private int bus;
    private int bcm;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException, IOException {
        // A loaded gpio mock so create()/provider-init have a real chip to open; the
        // context build/shutdown themselves are what we time, one per invocation.
        setup("gpio");
        this.bus = com.pi4j.bench.common.BenchProps.intProp("bench.gpio.bus", 97);
        this.bcm = com.pi4j.bench.common.BenchProps.intProp("bench.gpio.out.bcm", 5);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException, IOException {
        tearDown("gpio");
    }

    @Benchmark
    public void testCreateShutdown(Blackhole blackhole) {
        Context pi4j = Pi4J.newContextBuilder().add(new FFMDigitalOutputProviderImpl()).build();
        DigitalOutput pin = pi4j.create(DigitalOutputConfigBuilder.newInstance().bus(bus).bcm(bcm).build());
        blackhole.consume(pin);
        pi4j.shutdown();
    }

    @Benchmark
    public void testExplicitContext(Blackhole blackhole) {
        Context pi4j = Pi4J.newContextBuilder().add(new FFMDigitalOutputProviderImpl()).build();
        blackhole.consume(pi4j);
        pi4j.shutdown();
    }

    @Benchmark
    public void testAutoContext(Blackhole blackhole) {
        Context pi4j = Pi4J.newAutoContext();
        blackhole.consume(pi4j);
        pi4j.shutdown();
    }
}
