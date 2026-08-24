package com.pi4j.bench.latency;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalStateChangeListener;
import com.pi4j.io.gpio.digital.PullResistance;
import com.pi4j.plugin.ffm.providers.gpio.FFMDigitalInputProviderImpl;

import java.util.concurrent.TimeUnit;

/// V4 (FFM) lane: the pure-Java, `pi4j-plugin-ffm` edge path. Mirrors the pin the
/// hot-path suite uses — `bus(97).bcm(3)` on the `accessible` mock chip
/// (`bench-v4/src/test/resources/gpio-setup.sh`) — with debounce disabled so the
/// listener sees every raw edge. The FFM `EventWatcher` blocks in a native `poll()`
/// and dispatches on a daemon thread; our callback runs there.
final class V4Lane implements Lane {

    private Context pi4j;
    private DigitalInput pin;

    @Override
    public String name() {
        return "v4-ffm";
    }

    @Override
    public String debugfsLabel() {
        return com.pi4j.bench.common.BenchProps.strProp("bench.gpio.label.ffm", "accessible");
    }

    @Override
    public int lineOffset() {
        return com.pi4j.bench.common.BenchProps.intProp("bench.gpio.in.bcm", 3);
    }

    @Override
    public void open() {
        this.pi4j = Pi4J.newContextBuilder().add(new FFMDigitalInputProviderImpl()).build();
        var config = DigitalInputConfigBuilder.newInstance()
                .bus(com.pi4j.bench.common.BenchProps.intProp("bench.gpio.bus", 97))
                .bcm(com.pi4j.bench.common.BenchProps.intProp("bench.gpio.in.bcm", 3))
                .debounce(0L, TimeUnit.MICROSECONDS)
                .pull(PullResistance.PULL_DOWN)
                .build();
        this.pin = pi4j.create(config);
    }

    @Override
    public void onEdge(Runnable callback) {
        pin.addListener((DigitalStateChangeListener) _ -> callback.run());
    }

    @Override
    public void close() {
        if (pi4j != null) {
            pi4j.shutdown();
        }
    }
}
