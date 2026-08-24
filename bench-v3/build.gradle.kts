// Pi4J V3 lane (gpiod / linuxfs / pigpio). Mirrors bench-v4 class+method names.
// Plugin-free JMH — bench-runner forks `org.openjdk.jmh.Main` against this classpath.
val arch: String = System.getProperty("os.arch")

dependencies {
    implementation(project(":bench-common"))
    implementation(libs.pi4j.v3.core)
    implementation(libs.pi4j.v3.gpiod)
    implementation(libs.pi4j.v3.linuxfs)
    implementation(libs.pi4j.v3.pigpio)
    implementation(libs.jmh.core)
    implementation(libs.junit.jupiter.api) // BaseSetup uses Assertions.fail on setup/teardown failure
    annotationProcessor(libs.jmh.annprocess)
}

// The "провязка": both V3 gpiod AND linuxfs need JNI natives the official jars ship
// only for ARM. :native-v3:build compiles them locally (§5.4); the Pi4J v2/v3 loader
// honors `pi4j.library.path` → skips the (absent) in-jar lib. A single dir covers every
// V3 native (gpiod wrapper + underlying libgpiod + linuxfs wrapper). The runner passes
// this to JMH's forked JVMs via `-jvmArgsAppend`, hence sysProps in the lane spec.
val v3NativeDir = project(":native-v3").layout.buildDirectory.dir("libs/$arch")

tasks.register("writeLaneSpec") {
    group = "pi4j-bench"
    description = "Emit build/lanes/v3-jmh.properties for the Java orchestrator."
    dependsOn(":native-v3:build")
    val cp = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
    inputs.files(cp)
    val outFile = layout.buildDirectory.file("lanes/v3-jmh.properties")
    val workingDir = layout.projectDirectory.asFile
    val nativeDir = v3NativeDir
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("type=jmh")
                appendLine("mainClass=org.openjdk.jmh.Main")
                appendLine("workingDir=${workingDir.absolutePath}")
                appendLine("jvmArgs=--enable-native-access=ALL-UNNAMED -Xms512m -Xmx512m")
                appendLine("sysProps=pi4j.library.path=${nativeDir.get().asFile.absolutePath}")
                appendLine("classpath=${cp.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }}")
            },
        )
    }
}
