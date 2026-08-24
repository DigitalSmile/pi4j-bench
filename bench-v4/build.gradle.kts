// Pi4J V4 (FFM) lane. Same FQCN+method names as bench-v3 → merged charts line up.
// Plugin-free JMH: the annotation processor emits the benchmark list into the main
// output; bench-runner forks `org.openjdk.jmh.Main` against the classpath below
// (no me.champeau.jmh — orchestration lives in the Java runner now).
dependencies {
    implementation(project(":bench-common"))
    implementation(libs.pi4j.v4.core)
    implementation(libs.pi4j.v4.ffm)
    implementation(libs.jmh.core)
    implementation(libs.jna) // E1/E8 JNA rungs (the convenience-tax datapoint vs FFM/JNI)
    implementation(libs.junit.jupiter.api) // BaseSetup uses Assertions.fail on setup/teardown failure
    annotationProcessor(libs.jmh.annprocess)
}

// --- JNI helper .so for the E1/E8 baselines (gcc + the JDK's own JNI headers) ------------------
// A tiny libbenchjni.so so the FFM ladder/data-exchange benchmarks can compare against JNI on the
// same host. gcc is already required to build the mock drivers, so this adds no new prerequisite.
// Best-effort: if gcc is missing it logs and skips (the JNI benchmarks then report unavailable).
val buildJniHelper = tasks.register<Exec>("buildJniHelper") {
    group = "pi4j-bench"
    description = "Compile bench-v4 src/main/native/benchjni into build/native/libbenchjni.so."
    // Declared INSIDE the register block so the doFirst/doLast closures capture these locals, not
    // the enclosing build script (config-cache requirement — the sibling writeLaneSpec pattern).
    val src = layout.projectDirectory.file("src/main/native/benchjni/benchjni.c").asFile
    val so = layout.buildDirectory.file("native/libbenchjni.so").get().asFile
    val javaHome = System.getProperty("java.home")
    inputs.file(src)
    outputs.file(so)
    // The launching JDK provides jni.h / jni_md.h. Best-effort: don't fail the build if gcc is
    // absent — the JNI rungs then report unavailable (JniBridge.AVAILABLE == false).
    isIgnoreExitValue = true
    doFirst { so.parentFile.mkdirs() }
    commandLine(
        "gcc", "-shared", "-fPIC", "-O2",
        "-I$javaHome/include", "-I$javaHome/include/linux",
        src.absolutePath, "-o", so.absolutePath,
    )
    doLast {
        if (!so.exists()) {
            println("buildJniHelper: libbenchjni.so not produced (gcc missing?) — E1/E8 JNI rungs will report unavailable")
        }
    }
}
tasks.named("classes") { dependsOn(buildJniHelper) }

// Lane spec consumed by :bench-runner — mainClass + resolved classpath + working dir.
// JMH forks its own measurement JVMs, so per-JVM flags (--enable-native-access, heap)
// are applied by the runner via `-jvmArgsAppend`, not here.
tasks.register("writeLaneSpec") {
    group = "pi4j-bench"
    description = "Emit build/lanes/v4-jmh.properties for the Java orchestrator."
    dependsOn(buildJniHelper) // the JNI baseline .so must exist so the E1/E8 rungs can load it
    val cp = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
    inputs.files(cp)
    val outFile = layout.buildDirectory.file("lanes/v4-jmh.properties")
    val workingDir = layout.projectDirectory.asFile
    val jniLibFile = layout.buildDirectory.file("native/libbenchjni.so").get().asFile // config-cache safe local
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        // The JMH forks read bench.jni.lib (JniBridge) via -jvmArgsAppend from sysProps.
        val jniProp = if (jniLibFile.exists()) "bench.jni.lib=${jniLibFile.absolutePath}" else ""
        f.writeText(
            buildString {
                appendLine("type=jmh")
                appendLine("mainClass=org.openjdk.jmh.Main")
                appendLine("workingDir=${workingDir.absolutePath}")
                appendLine("jvmArgs=--enable-native-access=ALL-UNNAMED -Xms512m -Xmx512m")
                if (jniProp.isNotEmpty()) appendLine("sysProps=$jniProp")
                appendLine("classpath=${cp.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }}")
            },
        )
    }
}
