pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK 25 toolchain on amd64/arm64 (riscv64: manual Liberica, autodetected).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_PROJECT
    repositories {
        mavenCentral()
        mavenLocal() // Pi4J V4 FFM SNAPSHOT lane
    }
}

rootProject.name = "pi4j-bench"

include(
    "bench-common",
    "bench-v4",
    "bench-v3",
    "bench-latency",
    "bench-memory",
    "native-v3",
    "native-image",
)
