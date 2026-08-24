// Block E deep-dive — FFM API mechanics that need a custom harness, not JMH: virtual-thread
// carrier pinning on blocking downcalls (E4), time-to-safepoint with a native call in flight
// (E5), and Cleaner reclamation lag for Arena.ofAuto() (E6). Pure java.lang.foreign +
// bench-common — no Pi4J, no kernel mocks — so all three run on any JDK 25 host. Each emits its
// own lane spec; the orchestrator forks them as plain MAIN lanes (ffm-vthreads/-safepoint/-cleaner).
dependencies {
    implementation(project(":bench-common"))
    implementation(libs.hdrhistogram)
    // E4/E6 route their workload through real Pi4J FFM calls against the shared i2c mock, so the
    // numbers merge with the v4 lane (per the design decision). E5 stays raw usleep (safepoint).
    implementation(libs.pi4j.v4.core)
    implementation(libs.pi4j.v4.ffm)
}

// One writeLaneSpec task emitting all three ffm-* lane specs (collectLaneSpecs copies the whole
// build/lanes dir, so multiple files under one task are fine). type=main → the runner forks
// `java <jvmArgs> <MainClass>`; --enable-native-access is required for the usleep downcalls.
tasks.register("writeLaneSpec") {
    group = "pi4j-bench"
    description = "Emit build/lanes/ffm-{vthreads,safepoint,cleaner}.properties for the Java orchestrator."
    val cp = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
    inputs.files(cp)
    val workingDir = layout.projectDirectory.asFile
    val lanes = mapOf(
        "ffm-vthreads" to "com.pi4j.bench.ffm.VThreadPinningRunner",
        "ffm-safepoint" to "com.pi4j.bench.ffm.SafepointRunner",
        "ffm-cleaner" to "com.pi4j.bench.ffm.CleanerPressureRunner",
    )
    // Precompute the output files OUTSIDE doLast so the action captures serializable providers,
    // not the Gradle `layout` script object (config-cache requirement — see the sibling modules).
    val outByName = lanes.keys.associateWith { layout.buildDirectory.file("lanes/$it.properties") }
    outputs.files(outByName.values)
    doLast {
        val classpath = cp.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }
        lanes.forEach { (name, mainClass) ->
            val f = outByName.getValue(name).get().asFile
            f.parentFile.mkdirs()
            f.writeText(
                buildString {
                    appendLine("type=main")
                    appendLine("mainClass=$mainClass")
                    appendLine("workingDir=${workingDir.absolutePath}")
                    appendLine("jvmArgs=--enable-native-access=ALL-UNNAMED")
                    appendLine("classpath=$classpath")
                },
            )
        }
    }
}
