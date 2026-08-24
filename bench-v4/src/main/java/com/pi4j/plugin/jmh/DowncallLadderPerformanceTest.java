package com.pi4j.plugin.jmh;

import com.sun.jna.Library;
import com.sun.jna.Native;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/// Block E · E1 — Downcall option ladder: the raw cost of the FFM bridge with the kernel and
/// Pi4J out of the picture. Each `@Benchmark` is one rung; the deltas between them isolate the
/// price of the thread-state transition (`critical`) and of heap-access pinning
/// (`critical(true)` with a heap [MemorySegment] argument).
///
/// | rung | what it times |
/// |---|---|
/// | [#plainDowncall]     | a bare `getpid()` downcall — full thread-state transition |
/// | [#criticalDowncall]  | the same call with [Linker.Option#critical(boolean)] `(false)` — no transition |
/// | [#criticalNativeArg] | `strlen` on a *native* segment under `critical(false)` — pointer arg, no pinning |
/// | [#criticalHeapArg]   | `strlen` on a *heap* segment under `critical(true)` — the JVM must pin the array |
///
/// The full ladder adds the two cross-bridge baselines from the plan: [#jniDowncall] (rung d, via
/// the hand-built `libbenchjni.so`) and [#jnaDowncall] (rung e, JNA over libc). Expect plain FFM ≈
/// JNI, `critical` measurably cheaper than both, and JNA clearly behind — the exact numbers behind
/// the "cost of a downcall" card. AverageTime · ns/op · 3 forks, matching the Block A settings.
@Fork(value = 3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class DowncallLadderPerformanceTest {

    // A short null-terminated C string; strlen walks it to the terminator on every call.
    private static final byte[] C_STRING =
            "pi4j-bench-ffm-downcall-ladder\0".getBytes(StandardCharsets.US_ASCII);

    /// JNA binding to libc — rung (e), the convenience-tax datapoint. No custom `.so` needed.
    public interface CLib extends Library {
        int getpid();
    }

    private Arena arena;
    private MethodHandle getpidPlain;
    private MethodHandle getpidCritical;
    private MethodHandle strlenCritical;
    private MemorySegment heapCString;   // on-heap — crosses the boundary only under critical(true)
    private MemorySegment nativeCString; // off-heap — the baseline pointer argument
    private CLib jna;

    @Setup(Level.Trial)
    @SuppressWarnings("restricted") // libc downcalls are the whole point of the bench
    public void setup() {
        this.arena = Arena.ofShared();
        var linker = Linker.nativeLinker();
        var lookup = linker.defaultLookup();

        var getpid = lookup.find("getpid").orElseThrow(
                () -> new IllegalStateException("getpid not found in the default lookup"));
        this.getpidPlain = linker.downcallHandle(getpid, FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.getpidCritical = linker.downcallHandle(getpid, FunctionDescriptor.of(ValueLayout.JAVA_INT),
                Linker.Option.critical(false));

        var strlen = lookup.find("strlen").orElseThrow(
                () -> new IllegalStateException("strlen not found in the default lookup"));
        // critical(true) — allowHeapAccess — is what lets a heap segment be passed by reference.
        this.strlenCritical = linker.downcallHandle(strlen,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                Linker.Option.critical(true));

        this.heapCString = MemorySegment.ofArray(C_STRING);
        this.nativeCString = arena.allocate(C_STRING.length);
        MemorySegment.copy(C_STRING, 0, nativeCString, ValueLayout.JAVA_BYTE, 0, C_STRING.length);

        this.jna = Native.load("c", CLib.class);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    /// (a) Plain downcall — pays the Java↔native thread-state transition on entry and exit.
    @Benchmark
    public int plainDowncall() throws Throwable {
        return (int) getpidPlain.invokeExact();
    }

    /// (b) `critical(false)` — the JVM skips the thread-state transition; the delta vs
    /// [#plainDowncall] is the price of that transition.
    @Benchmark
    public int criticalDowncall() throws Throwable {
        return (int) getpidCritical.invokeExact();
    }

    /// (c) `critical(true)` with an *off-heap* pointer — the safe baseline for the heap
    /// comparison below (no array to pin, so no pinning tax).
    @Benchmark
    public long criticalNativeArg() throws Throwable {
        return (long) strlenCritical.invokeExact(nativeCString);
    }

    /// (d') `critical(true)` with an *on-heap* segment — the delta vs [#criticalNativeArg] is
    /// the cost of pinning the backing array for the duration of the call.
    @Benchmark
    public long criticalHeapArg() throws Throwable {
        return (long) strlenCritical.invokeExact(heapCString);
    }

    /// (d) JNI downcall — the classic bridge; expect it ≈ plain FFM.
    @Benchmark
    public int jniDowncall() {
        return JniBridge.AVAILABLE ? JniBridge.getpid() : 0;
    }

    /// (e) JNA downcall — the convenience tax; expect it clearly behind FFM and JNI.
    @Benchmark
    public int jnaDowncall() {
        return jna.getpid();
    }
}
