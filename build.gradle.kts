// Root = aggregator. The whole matrix runs from a single `benchAll` entrypoint (§4.3).
// Ordering: lifecycle (cold JVMs) → hot path → memory → latency (longest) → merge.
plugins {
    id("pi4j-bench.results")
}

group = "io.github.digitalsmile"
version = "1.0-SNAPSHOT"

val lane = providers.gradleProperty("lane").getOrElse("mock") // mock | hw
val arch: String = System.getProperty("os.arch")

// --- environment gate --------------------------------------------------------
tasks.register("envCheck") {
    group = "pi4j-bench"
    description = "Verifies governor/taskset/sudoers/libgpiod before any measurement (fails with hints)."
    doLast {
        logger.lifecycle("envCheck: arch=$arch lane=$lane — TODO: governor=performance, taskset, sudoers, libgpiod v1.x")
    }
}

// --- mock kernel drivers (skipped when lane=hw) ------------------------------
tasks.register("buildMocks") {
    group = "pi4j-bench"
    description = "make in pi4j-plugin-ffm/src/test/native/* (incremental via inputs/outputs)."
    doLast { logger.lifecycle("buildMocks: TODO wire pi4j FFM mock Makefiles") }
}
tasks.register("loadMocks") {
    group = "pi4j-bench"
    description = "sudo *-setup.sh (insmod). No-op when lane=hw."
    dependsOn("buildMocks")
    onlyIf { lane == "mock" }
    doLast { logger.lifecycle("loadMocks: TODO insmod mock drivers") }
}
tasks.register("unloadMocks") {
    group = "pi4j-bench"
    description = "sudo *-clean.sh (rmmod)."
    onlyIf { lane == "mock" }
    doLast { logger.lifecycle("unloadMocks: TODO rmmod mock drivers") }
}

// --- benchmark blocks --------------------------------------------------------
tasks.register("benchHotPath") {
    group = "pi4j-bench"
    description = "Block A — GPIO/I2C/SPI/PWM hot path across V3+V4."
    dependsOn(":bench-v4:jmh", ":bench-v3:jmh")
}
tasks.register("benchLifecycle") {
    group = "pi4j-bench"
    description = "Block B — provider create/shutdown, autoContext vs explicit."
    dependsOn(":bench-v4:jmh", ":bench-v3:jmh")
}
tasks.register("benchLatency") {
    group = "pi4j-bench"
    description = "Block C — edge event → listener latency/jitter (HdrHistogram, jHiccup, JFR)."
    dependsOn(":bench-latency:run")
}
tasks.register("benchMemory") {
    group = "pi4j-bench"
    description = "Block D — RSS/NMT trajectory + B/op (#628 repro)."
    dependsOn(":bench-memory:run")
}

tasks.register("mergeReports") {
    group = "pi4j-bench"
    description = "Merge V3/V4 JSON + HDR + CSV + fingerprint into results/<arch>/<lane>/<ts>/."
    dependsOn("stampEnvManifest")
    doLast { logger.lifecycle("mergeReports: TODO collate JMH JSON + HDR + fingerprint.json") }
}

tasks.register("benchAll") {
    group = "pi4j-bench"
    description = "Single entrypoint: envCheck → mocks → lifecycle → hot path → memory → latency → merge."
    dependsOn(
        "envCheck", "loadMocks",
        "benchLifecycle", "benchHotPath", "benchMemory", "benchLatency",
        "mergeReports",
    )
    finalizedBy("unloadMocks")
}
