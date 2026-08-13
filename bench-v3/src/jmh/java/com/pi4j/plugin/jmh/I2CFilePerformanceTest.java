package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.i2c.I2C;
import com.pi4j.io.i2c.I2CConfigBuilder;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.linuxfs.provider.i2c.LinuxFsI2CProviderImpl;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/// V3 mirror of the FFM I2C hot path. V3 has no I2CImplementation enum; the linuxfs
/// provider is file-based (closest to FFM's FILE mode) — this is the fair V3 anchor.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class I2CFilePerformanceTest extends BaseSetup {

    private Context pi4j;
    private I2C i2c;

    @Setup
    public void setup() throws InterruptedException, IOException {
        setup("i2c");
        this.pi4j = Pi4J.newContextBuilder().add(new LinuxFsI2CProviderImpl()).build();
        this.i2c = pi4j.create(I2CConfigBuilder.newInstance(pi4j).bus(99).device(0x1C).build());
    }

    @TearDown
    public void shutdown() throws InterruptedException, IOException {
        pi4j.shutdown();
        tearDown("i2c");
    }

    @Benchmark
    public void testLinuxFsI2CRoundTrip() {
        var writeBuffer = new byte[]{0x01, 0x02, 0x03};
        i2c.writeRegister(0xFF, writeBuffer);
        var readBuffer = new byte[3];
        i2c.readRegister(0xFF, readBuffer);
        if (!Arrays.equals(readBuffer, writeBuffer)) {
            throw new RuntimeException("Read buffer mismatch: read[" + Arrays.toString(readBuffer) + "]," +
                " write[" + Arrays.toString(writeBuffer) + "]");
        }
    }
}
