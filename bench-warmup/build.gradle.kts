// FFM warmup microbenchmark — measures time-to-steady-state of a `java.lang.foreign`
// downcall (libc `getpid`) from a cold JVM. This isolates exactly what an AOT cache /
// Leyden targets: the JIT ramp of the FFM downcall stub. Pure FFM + bench-common — no
// Pi4J, no kernel mocks — so the three warmup variants (baseline JIT, JDK 25 AOT cache
// per JEP 483, Leyden EA) run anywhere. The orchestrator drives the AOT/Leyden variants;
// this module just emits one lane spec.
dependencies {
    implementation(project(":bench-common"))
    implementation(libs.hdrhistogram)
}

tasks.register("writeLaneSpec") {
    group = "pi4j-bench"
    description = "Emit build/lanes/warmup-v4.properties for the Java orchestrator."
    val cp = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
    inputs.files(cp)
    val outFile = layout.buildDirectory.file("lanes/warmup-v4.properties")
    val workingDir = layout.projectDirectory.asFile
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("type=main")
                appendLine("mainClass=com.pi4j.bench.warmup.WarmupRunner")
                appendLine("workingDir=${workingDir.absolutePath}")
                appendLine("jvmArgs=--enable-native-access=ALL-UNNAMED")
                appendLine("classpath=${cp.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }}")
            },
        )
    }
}
