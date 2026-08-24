package com.pi4j.plugin.jmh;

import com.pi4j.bench.common.GcPressure;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;

/// Block E · E-GC — Hot-path FFM call under allocation pressure: does an FFM downcall itself get
/// slower while the application is churning garbage? This isolates the *call* (unlike Block C,
/// which measures the whole edge→listener pipeline), so a fatter tail here belongs to the
/// call-boundary machinery — safepoint checks, thread-state/GC-barrier interaction, allocator
/// cache pollution — not to the poll/scheduler.
///
/// One measured `getpid()` downcall, with a background allocator ([GcPressure]) churning at
/// `allocMbps` MB/s in the same JVM. `Mode.SampleTime` is mandatory: average-time would smear the
/// pauses into the mean, but the signal lives entirely in the p99 / p99.9 tail. Run the matrix on
/// both G1 and ZGC (the orchestrator's `--gc` / `--gc-matrix`); the story is a median that barely
/// moves and a tail that grows with pressure — the collector's tail, not the bridge's.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HotPathPressurePerformanceTest {

    /// Background churn rate in MB/s; 0 is the zero-pressure baseline every percentile is compared
    /// against. Matches the plan's `@Param({"0", "200", "1000"})`.
    @Param({"0", "200", "1000"})
    private int allocMbps;

    private MethodHandle getpid;
    private GcPressure pressure;

    @Setup(Level.Trial)
    @SuppressWarnings("restricted") // the getpid downcall is the measured hot path
    public void setup() {
        var linker = Linker.nativeLinker();
        this.getpid = linker.downcallHandle(
                linker.defaultLookup().find("getpid").orElseThrow(
                        () -> new IllegalStateException("getpid not found in the default lookup")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
                Linker.Option.critical(false));
        if (allocMbps > 0) {
            this.pressure = GcPressure.start(allocMbps);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pressure != null) {
            pressure.close();
        }
    }

    @Benchmark
    public int hotDowncall() throws Throwable {
        return (int) getpid.invokeExact();
    }
}
