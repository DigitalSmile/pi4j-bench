package com.pi4j.plugin.jmh;

import com.sun.jna.Memory;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

/// Block E · E8 — Data exchange across the boundary: for real workloads the payload matters more
/// than the call, so what does moving bytes cost each way? Transfers a heap `byte[]` ↔ native
/// memory at 64 B / 4 KiB / 1 MiB, both directions, via [MemorySegment#copy]. FFM bulk copy rides
/// an `Unsafe.copyMemory`-class intrinsic and should match or beat JNI `Get/SetByteArrayRegion`
/// while staying safe.
///
/// The full sweep adds the plan's cross-bridge baselines: JNI `Get/SetByteArrayRegion`
/// ([#jniHeapToNative] / [#jniNativeToHeap]), the fast-but-dangerous `GetPrimitiveArrayCritical`
/// ([#jniHeapToNativeCritical], via `libbenchjni.so`), and JNA `Memory.read/write`
/// ([#jnaHeapToNative] / [#jnaNativeToHeap]). Expect FFM bulk copy ≈ Get/SetByteArrayRegion while
/// staying safe, and JNA trailing. AverageTime · ns/op; derive GB/s as `bytes / (ns/op)`. The
/// crossover — where bulk copy amortises the fixed call overhead — lives in the 64 B vs 4 KiB gap.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class DataExchangePerformanceTest {

    @Param({"64", "4096", "1048576"})
    private int bytes;

    private Arena arena;
    private MemorySegment nativeSeg;
    private long nativeAddr;         // nativeSeg.address() — handed to the JNI region ops
    private byte[] heapArray;
    private MemorySegment heapView;  // a heap segment view over heapArray — the "heap side"
    private Memory jnaMem;           // JNA off-heap buffer — the convenience-tax datapoint

    @Setup(Level.Trial)
    public void setup() {
        this.arena = Arena.ofShared();
        this.nativeSeg = arena.allocate(bytes);
        this.nativeAddr = nativeSeg.address();
        this.heapArray = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            heapArray[i] = (byte) i;
        }
        this.heapView = MemorySegment.ofArray(heapArray);
        this.jnaMem = new Memory(bytes);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
        jnaMem.close();
    }

    /// heap `byte[]` → native memory (the write direction, e.g. an SPI/I2C payload out).
    @Benchmark
    public void heapToNative(Blackhole blackhole) {
        MemorySegment.copy(heapView, 0, nativeSeg, 0, bytes);
        blackhole.consume(nativeSeg.get(ValueLayout.JAVA_BYTE, 0));
    }

    /// native memory → heap `byte[]` (the read direction, e.g. an SPI/I2C payload in).
    @Benchmark
    public void nativeToHeap(Blackhole blackhole) {
        MemorySegment.copy(nativeSeg, 0, heapView, 0, bytes);
        blackhole.consume(heapArray[0]);
    }

    /// JNI `GetByteArrayRegion`: heap `byte[]` → native buffer (write direction).
    @Benchmark
    public void jniHeapToNative(Blackhole blackhole) {
        if (!JniBridge.AVAILABLE) {
            blackhole.consume(0);
            return;
        }
        JniBridge.heapToNativeRegion(heapArray, nativeAddr, bytes);
        blackhole.consume(nativeSeg.get(ValueLayout.JAVA_BYTE, 0));
    }

    /// JNI `SetByteArrayRegion`: native buffer → heap `byte[]` (read direction).
    @Benchmark
    public void jniNativeToHeap(Blackhole blackhole) {
        if (!JniBridge.AVAILABLE) {
            blackhole.consume(0);
            return;
        }
        JniBridge.nativeToHeapRegion(heapArray, nativeAddr, bytes);
        blackhole.consume(heapArray[0]);
    }

    /// JNI `GetPrimitiveArrayCritical` — the fast-but-dangerous baseline (pins the array).
    @Benchmark
    public void jniHeapToNativeCritical(Blackhole blackhole) {
        if (!JniBridge.AVAILABLE) {
            blackhole.consume(0);
            return;
        }
        JniBridge.heapToNativeCritical(heapArray, nativeAddr, bytes);
        blackhole.consume(nativeSeg.get(ValueLayout.JAVA_BYTE, 0));
    }

    /// JNA `Memory.write`: heap `byte[]` → native buffer (write direction).
    @Benchmark
    public void jnaHeapToNative(Blackhole blackhole) {
        jnaMem.write(0, heapArray, 0, bytes);
        blackhole.consume(jnaMem.getByte(0));
    }

    /// JNA `Memory.read`: native buffer → heap `byte[]` (read direction).
    @Benchmark
    public void jnaNativeToHeap(Blackhole blackhole) {
        jnaMem.read(0, heapArray, 0, bytes);
        blackhole.consume(heapArray[0]);
    }
}
