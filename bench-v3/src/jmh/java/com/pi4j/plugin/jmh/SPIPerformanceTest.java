package com.pi4j.plugin.jmh;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiBus;
import com.pi4j.io.spi.SpiConfigBuilder;
import com.pi4j.plugin.BaseSetup;
import com.pi4j.plugin.linuxfs.provider.spi.LinuxFsSpiProviderImpl;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// V3 mirror of the FFM SPI hot path. linuxfs SPI is the portable V3 lane
/// (pigpio SPI is ARM-only, added on the HW lane).
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SPIPerformanceTest extends BaseSetup {

    private Context pi4j;
    private Spi spi;

    @Setup
    public void setup() throws InterruptedException, IOException {
        setup("spi");
        this.pi4j = Pi4J.newContextBuilder().add(new LinuxFsSpiProviderImpl()).build();
        this.spi = pi4j.create(SpiConfigBuilder.newInstance(pi4j)
            .bus(SpiBus.BUS_6)
            .channel(0)
            .mode(0)
            .baud(50_000)
            .build());
    }

    @TearDown
    public void shutdown() throws InterruptedException, IOException {
        pi4j.shutdown();
        tearDown("spi");
    }

    private static final Random random = new Random();

    @Benchmark
    public void testLinuxFsWriteReadRoundTrip() {
        var str = String.valueOf(random.nextInt(1, 1024));
        var writeBuffer = str.getBytes();
        var readBuffer = new byte[str.length()];
        spi.transfer(writeBuffer, readBuffer);
        if (!Arrays.equals(readBuffer, writeBuffer)) {
            throw new RuntimeException("Read buffer mismatch: read[" + Arrays.toString(readBuffer) + "]," +
                " write[" + Arrays.toString(writeBuffer) + "]");
        }
    }
}
