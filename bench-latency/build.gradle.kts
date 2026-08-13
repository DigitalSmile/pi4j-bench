// Block C — latency & jitter runner (custom main, NOT JMH). Flavor via -Pv=3|4.
plugins {
    id("pi4j-bench.java")
    id("pi4j-bench.results")
    application
}

val v = providers.gradleProperty("v").getOrElse("4") // 3 | 4

dependencies {
    implementation(project(":bench-common"))
    implementation(libs.hdrhistogram)
    if (v == "3") {
        implementation(libs.pi4j.v3.core)
        implementation(libs.pi4j.v3.gpiod)
    } else {
        implementation(libs.pi4j.v4.core)
        implementation(libs.pi4j.v4.ffm)
    }
}

application {
    mainClass = "com.pi4j.bench.latency.LatencyRunner"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:NativeMemoryTracking=summary",
    )
}
