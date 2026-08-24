// GraalVM native-image lane for the V4 (FFM) latency runner — Block C's "native image
// flattens warmup" story (§6.3.4/.5). Reuses bench-latency's V4 flavor verbatim; the
// only new thing here is an AOT image of com.pi4j.bench.latency.LatencyRunner.
//
// FFM in native-image: downcall stubs must be registered. Pi4J builds its
// FunctionDescriptors at class-init and opens /dev/gpiochipN at runtime, so the reliable
// route is the tracing agent (foreign-config + reflect/resource metadata) captured from a
// JIT run against a loaded mock — then nativeCompile bakes it in. Workflow:
//
//   # 1. capture metadata (needs the gpio mock loaded + debugfs writable)
//   ./gradlew :native-image:run -Pagent --args="--warmup-seconds=2 --measure-seconds=2"
//   ./gradlew :native-image:metadataCopy --task run --dir src/main/resources/META-INF/native-image
//   # 2. build + run the AOT image (flat warmup, no JIT ramp)
//   ./gradlew :native-image:nativeCompile
//   ./native-image/build/native/nativeCompile/latency-v4-native --warmup-seconds=5 --measure-seconds=30
plugins {
    application
    alias(libs.plugins.graalvm.native)
}

dependencies {
    // Default -Pv=4: pulls LatencyRunner + V4Lane + FFM provider + bench-common.
    implementation(project(":bench-latency"))
}

application {
    mainClass = "com.pi4j.bench.latency.LatencyRunner"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    // Pick the GraalVM toolchain from org.gradle.java.installations.paths (graalce 25).
    toolchainDetection = true
    // Be explicit about the launcher: the surrounding build usually runs on a plain JDK
    // (no native-image), so pin the native binary to the GraalVM 25 toolchain by vendor.
    binaries.configureEach {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.GRAAL_VM
        }
    }
    // GraalVM reachability-metadata repository: community-maintained config for common
    // libraries, merged with whatever the agent captures for Pi4J.
    metadataRepository {
        enabled = true
    }
    binaries {
        named("main") {
            imageName = "latency-v4-native"
            mainClass = "com.pi4j.bench.latency.LatencyRunner"
            // Match the JIT lane's JVM args (bench-latency application) at build time.
            buildArgs.addAll(
                "--enable-native-access=ALL-UNNAMED",
                "-H:+UnlockExperimentalVMOptions",
                // Pi4J providers open native fds/parse /proc at runtime — never at build.
                "--initialize-at-run-time=com.pi4j.plugin.ffm,com.pi4j.boardinfo",
                "-H:+ReportExceptionStackTraces",
            )
            // A native image has no JIT, so the warmup curve should already be flat;
            // still record it so slide 18 can overlay JIT-vs-AOT.
            runtimeArgs.addAll("--warmup-seconds=5", "--measure-seconds=30")
        }
    }
    // ./gradlew :native-image:run -Pagent  → tracing agent writes metadata under
    // build/native/agent-output/run, then `metadataCopy` promotes it into resources.
    agent {
        defaultMode = "standard"
        metadataCopy {
            inputTaskNames.add("run")
            outputDirectories.add("src/main/resources/META-INF/native-image")
            mergeWithExisting = true
        }
    }
}

// --- lane spec (NATIVE) → collected by the orchestrator like every other lane -------------
// Emits build/lanes/latency-v4-native.properties pointing at the AOT binary. It does NOT
// depend on nativeCompile (that's opt-in / heavy): the runner only runs this lane when
// `--native`/`run.native` is set AND the binary is actually present.
tasks.register("writeLaneSpec") {
    group = "pi4j-bench"
    description = "Emit build/lanes/latency-v4-native.properties (NATIVE lane; binary from nativeCompile)."
    val outFile = layout.buildDirectory.file("lanes/latency-v4-native.properties")
    val binary = layout.buildDirectory.file("native/nativeCompile/latency-v4-native").get().asFile
    val wd = layout.projectDirectory.asFile
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("type=native")
                appendLine("mainClass=com.pi4j.bench.latency.LatencyRunner")
                appendLine("workingDir=${wd.absolutePath}")
                appendLine("binary=${binary.absolutePath}")
            },
        )
    }
}

// GraalVM Native Build Tools 0.10.6 resolves legacy configurations at store time, which
// the project-wide configuration cache (gradle.properties) rejects. Opt its tasks out —
// so the native lane runs;
// native-image forks its own process and doesn't benefit from the cache anyway.
tasks.matching {
    it.name in setOf("generateResourcesConfigFile", "nativeCompile", "nativeRun",
        "metadataCopy", "collectReachabilityMetadata")
}.configureEach {
    // (The old jmhReport task had the same opt-out before JMH moved off the Gradle plugin.)
    notCompatibleWithConfigurationCache(
        "GraalVM Native Build Tools 0.10.6 is not configuration-cache compatible")
}
