// Zero Pi4J deps: MockSetup, RssSampler, EnvManifest, HdrRecorder, result-schema POJOs.
// Shared by every lane so the harness code is identical across V3/V4.
plugins {
    `java-library`
}

dependencies {
    api(libs.hdrhistogram)
    api(libs.oshi.core)
    api(libs.jackson.databind)
    // Single SLF4J binding for every lane (pi4j + oshi log through slf4j).
    // Silenced by simplelogger.properties (defaultLogLevel=off) in this module.
    api(libs.slf4j.simple)

    // Harness unit tests (sysfs PWM chip discovery). The lanes themselves measure hardware
    // and cannot run here; this covers the pure-Java logic they depend on.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
