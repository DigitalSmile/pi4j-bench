// JNI baseline for the Block E FFM comparisons (E1 downcall ladder, E8 data exchange). A tiny
// hand-built helper so the talk can show "FFM downcall ≈ JNI" and "FFM bulk copy ≈ JNI array
// regions" with real numbers on the same host. Built by :bench-v4:buildJniHelper (gcc + the JDK
// JNI headers) into build/native/libbenchjni.so; the v4-jmh lane spec points JniBridge at it via
// -Dbench.jni.lib. Native buffers are passed as raw addresses (a MemorySegment.address() long),
// so the Java side owns their lifetime.
#include <jni.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>

// E1 rung (d): a trivial JNI downcall — the classic JNI equivalent of the FFM getpid handle.
JNIEXPORT jint JNICALL
Java_com_pi4j_plugin_jmh_JniBridge_getpid(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return (jint) getpid();
}

// E8 rung (b), write direction: heap byte[] -> native buffer via GetByteArrayRegion.
JNIEXPORT void JNICALL
Java_com_pi4j_plugin_jmh_JniBridge_heapToNativeRegion(JNIEnv *env, jclass cls,
                                                      jbyteArray src, jlong dstAddr, jint len) {
    (void) cls;
    (*env)->GetByteArrayRegion(env, src, 0, len, (jbyte *) (intptr_t) dstAddr);
}

// E8 rung (b), read direction: native buffer -> heap byte[] via SetByteArrayRegion.
JNIEXPORT void JNICALL
Java_com_pi4j_plugin_jmh_JniBridge_nativeToHeapRegion(JNIEnv *env, jclass cls,
                                                      jbyteArray dst, jlong srcAddr, jint len) {
    (void) cls;
    (*env)->SetByteArrayRegion(env, dst, 0, len, (const jbyte *) (intptr_t) srcAddr);
}

// E8 rung (c): the fast-but-dangerous GetPrimitiveArrayCritical baseline — pins the array (may
// block GC) and memcpy's it. The FFM bulk copy should match this while staying safe.
JNIEXPORT void JNICALL
Java_com_pi4j_plugin_jmh_JniBridge_heapToNativeCritical(JNIEnv *env, jclass cls,
                                                        jbyteArray src, jlong dstAddr, jint len) {
    (void) cls;
    void *p = (*env)->GetPrimitiveArrayCritical(env, src, 0);
    if (p != NULL) {
        memcpy((void *) (intptr_t) dstAddr, p, (size_t) len);
        (*env)->ReleasePrimitiveArrayCritical(env, src, p, 0);
    }
}
