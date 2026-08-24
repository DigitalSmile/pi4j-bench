package com.pi4j.plugin.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/// Block E · E7 — Bulk access inside a segment: is FFM any faster than good old `ByteBuffer`?
/// Fills then sums a region four ways at 4 KiB and 1 MiB:
///
/// | rung | how it reads/writes the region |
/// |---|---|
/// | [#varHandleLoop]   | [MemorySegment] element access through a **static-final** [VarHandle] |
/// | [#bulkCopy]        | [MemorySegment#copyFrom] — one intrinsified bulk move |
/// | [#byteBufferLoop]  | a direct [ByteBuffer] `putInt`/`getInt` loop |
/// | [#heapArrayLoop]   | a plain `int[]` on-heap baseline |
/// | [#nonConstantVarHandle] | the same segment loop through a **non-constant** handle |
///
/// The last rung is the trap: a warmed-up static-final `VarHandle` lets C2 hoist bounds/liveness
/// checks out of the loop and matches the direct buffer, but reading through a non-constant handle
/// defeats that and throughput collapses — the evidence behind the "cache your handles" rule.
/// Run with `-prof perfnorm` on amd64 to see instructions-per-element drop for the constant handle.
/// AverageTime · µs/op; derive GB/s in the report as `bytes / (µs/op)`.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SegmentAccessPerformanceTest {

    /// Constant handle — C2 sees the receiver as a compile-time constant and optimises the loop.
    private static final VarHandle INT_VH = ValueLayout.JAVA_INT.varHandle();

    @Param({"4096", "1048576"})
    private int bytes;

    private Arena arena;
    private MemorySegment dst;
    private MemorySegment src;
    private ByteBuffer directBuf;
    private int[] heapInts;
    private int ints;

    /// A per-instance copy of the same handle: not a compile-time constant, so C2 cannot fold it.
    private VarHandle nonConstVh;

    @Setup(Level.Trial)
    public void setup() {
        this.arena = Arena.ofShared();
        this.ints = bytes / Integer.BYTES;
        this.dst = arena.allocate(bytes);
        this.src = arena.allocate(bytes);
        for (int i = 0; i < ints; i++) {
            src.setAtIndex(ValueLayout.JAVA_INT, i, i);
        }
        this.directBuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        this.heapInts = new int[ints];
        this.nonConstVh = ValueLayout.JAVA_INT.varHandle();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public long varHandleLoop() {
        long sum = 0;
        for (int i = 0; i < ints; i++) {
            long off = (long) i * Integer.BYTES;
            INT_VH.set(dst, off, i);
            sum += (int) INT_VH.get(dst, off);
        }
        return sum;
    }

    @Benchmark
    public void bulkCopy(Blackhole blackhole) {
        dst.copyFrom(src);
        blackhole.consume(dst.get(ValueLayout.JAVA_INT, 0));
    }

    @Benchmark
    public long byteBufferLoop() {
        long sum = 0;
        for (int i = 0; i < ints; i++) {
            int off = i * Integer.BYTES;
            directBuf.putInt(off, i);
            sum += directBuf.getInt(off);
        }
        return sum;
    }

    @Benchmark
    public long heapArrayLoop() {
        long sum = 0;
        for (int i = 0; i < ints; i++) {
            heapInts[i] = i;
            sum += heapInts[i];
        }
        return sum;
    }

    @Benchmark
    public long nonConstantVarHandle() {
        long sum = 0;
        for (int i = 0; i < ints; i++) {
            long off = (long) i * Integer.BYTES;
            nonConstVh.set(dst, off, i);
            sum += (int) nonConstVh.get(dst, off);
        }
        return sum;
    }
}
