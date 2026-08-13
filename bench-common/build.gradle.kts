// Zero Pi4J deps: MockSetup, RssSampler, EnvManifest, HdrRecorder, result-schema POJOs.
// Shared by every lane so the harness code is identical across V3/V4.
plugins {
    id("pi4j-bench.java")
    `java-library`
}

dependencies {
    api(libs.hdrhistogram)
    api(libs.oshi.core)
    api(libs.jackson.databind)
    // Single SLF4J binding for every lane (pi4j + oshi log through slf4j).
    // Silenced by simplelogger.properties (defaultLogLevel=off) in this module.
    api(libs.slf4j.simple)
}
