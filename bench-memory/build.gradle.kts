// Block D — RSS/NMT trajectory + B/op. Modes: --mode=fixed | --mode=issue628.
plugins {
    id("pi4j-bench.java")
    id("pi4j-bench.results")
    application
}

val v = providers.gradleProperty("v").getOrElse("4") // 3 | 4

// The #628 repro pins the pre-fix V4 tag in a dedicated configuration.
val issue628 by configurations.creating

dependencies {
    implementation(project(":bench-common"))
    if (v == "3") {
        implementation(libs.pi4j.v3.core)
        implementation(libs.pi4j.v3.linuxfs)
    } else {
        implementation(libs.pi4j.v4.core)
        implementation(libs.pi4j.v4.ffm)
    }
    // pre-fix v4.0.1 dependency lives here, swapped in for --mode=issue628 runs.
    issue628("com.pi4j:pi4j-plugin-ffm:4.0.1")
}

application {
    mainClass = "com.pi4j.bench.memory.MemoryRunner"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:NativeMemoryTracking=summary",
    )
}
