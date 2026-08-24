// Block C — latency & jitter runner (custom main, NOT JMH). Flavor via -Pv=3|4.
// A plain library: bench-runner forks LatencyRunner against the classpath below, and
// native-image reuses these compiled classes. GC/JFR/jHiccup and results.dir are
// launch choices the orchestrator injects per run — not baked in here anymore.
val v = providers.gradleProperty("v").getOrElse("4") // 3 | 4
val arch: String = System.getProperty("os.arch")

// Only the selected flavor's Lane (src/v3|v4/java) joins the main source set, so the
// two Pi4J `pi4j-core` GAVs never share a classpath (Appendix A same-GAV trap).
sourceSets["main"].java.srcDir("src/v$v/java")

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

// The V3 gpiod flavor needs the locally built JNI natives on the library path.
val v3NativeDir = project(":native-v3").layout.buildDirectory.dir("libs/$arch")

tasks.register("writeLaneSpec") {
    group = "pi4j-bench"
    description = "Emit build/lanes/latency-v$v.properties for the Java orchestrator."
    if (v == "3") dependsOn(":native-v3:build")
    val cp = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
    inputs.files(cp)
    val outFile = layout.buildDirectory.file("lanes/latency-v$v.properties")
    val workingDir = layout.projectDirectory.asFile
    val flavor = v
    val nativeDir = v3NativeDir
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("type=main")
                appendLine("mainClass=com.pi4j.bench.latency.LatencyRunner")
                appendLine("workingDir=${workingDir.absolutePath}")
                appendLine("jvmArgs=--enable-native-access=ALL-UNNAMED -XX:NativeMemoryTracking=summary")
                if (flavor == "3") {
                    appendLine("sysProps=pi4j.library.path=${nativeDir.get().asFile.absolutePath}")
                }
                appendLine("classpath=${cp.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }}")
            },
        )
    }
}
