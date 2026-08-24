package com.pi4j.plugin.jmh;

import java.nio.file.Files;
import java.nio.file.Path;

/// Loads the hand-built `libbenchjni.so` and exposes its JNI entry points as the JNI baseline for
/// the Block E FFM comparisons (E1 downcall ladder, E8 data exchange). The library is resolved
/// from `-Dbench.jni.lib` (set by the `v4-jmh` lane spec, which depends on `:bench-v4:buildJniHelper`)
/// or, failing that, the default build path — so a plain `./gradlew :bench-v4:buildJniHelper` run
/// works standalone too. [#AVAILABLE] is false when the `.so` isn't present; the E1/E8 setups check
/// it and fail with a clear hint rather than an opaque `UnsatisfiedLinkError` mid-measurement.
final class JniBridge {

    static final boolean AVAILABLE = load();

    private JniBridge() {
    }

    /// E1 (d): a trivial JNI downcall — the JNI equivalent of the FFM `getpid` handle.
    static native int getpid();

    /// E8 (b) write: heap `byte[]` → native buffer at `dstAddr` via `GetByteArrayRegion`.
    static native void heapToNativeRegion(byte[] src, long dstAddr, int len);

    /// E8 (b) read: native buffer at `srcAddr` → heap `byte[]` via `SetByteArrayRegion`.
    static native void nativeToHeapRegion(byte[] dst, long srcAddr, int len);

    /// E8 (c): the `GetPrimitiveArrayCritical` fast path (pins the array, memcpy's it).
    static native void heapToNativeCritical(byte[] src, long dstAddr, int len);

    private static boolean load() {
        var prop = System.getProperty("bench.jni.lib");
        try {
            if (prop != null && !prop.isBlank()) {
                System.load(prop);
                return true;
            }
            var def = Path.of("build/native/libbenchjni.so").toAbsolutePath();
            if (Files.exists(def)) {
                System.load(def.toString());
                return true;
            }
        } catch (Throwable t) {
            // fall through — reported via AVAILABLE, not a hard failure at class-init time
        }
        return false;
    }

    /// Message for a JMH `@Setup` to fail with when [#AVAILABLE] is false.
    static String unavailable() {
        return "JNI helper libbenchjni.so not loaded — build it with `:bench-v4:buildJniHelper` "
                + "(needs gcc + JDK headers); the v4-jmh lane wires -Dbench.jni.lib automatically.";
    }
}
