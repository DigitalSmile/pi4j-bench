// Block D — RSS/NMT trajectory (#628 repro). V4/FFM only: the leak is the FFM native
// arena (shared ofAuto → ramp) vs the fix (per-call ofConfined → flat). Both lanes are
// Maven Central 4.0.x releases; the runner is compiled once against the fixed API and
// the *runtime* classpath decides the behaviour. bench-runner forks MemoryRunner twice,
// once per lane spec below — the fixed 4.0.2 classpath and the pre-fix 4.0.1 classpath.
val fixedVer = "4.0.2" // per-call Arena.ofConfined() — footprint stays flat
val bugVer = "4.0.1"   // pre-fix shared Arena.ofAuto() — RSS ramps (#628)

// Self-contained pre-fix classpath, swapped in for the issue628 run (never on the
// compile/fixed classpath — 4.0.1 and 4.0.2 carry the same GAV/classes).
val issue628 by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":bench-common"))
    // Fixed lane = compile classpath. Pinned to 4.0.x Central (its
    // I2CConfigBuilder.newInstance(Context) differs from 5.0.0-SNAPSHOT's no-arg form).
    implementation("com.pi4j:pi4j-core:$fixedVer")
    implementation("com.pi4j:pi4j-plugin-ffm:$fixedVer")
    // Pre-fix lane, fully self-contained (core + ffm + bench-common) so it can be forked
    // with 4.0.1 alone and no 4.0.2 leaks onto the classpath.
    issue628("com.pi4j:pi4j-core:$bugVer")
    issue628("com.pi4j:pi4j-plugin-ffm:$bugVer")
    issue628(project(":bench-common"))
}

// A modest fixed heap keeps the on-heap baseline stable so the off-heap native arena
// ramp (issue628) stands out in RSS instead of being masked by heap growth.
val memJvmArgs = "--enable-native-access=ALL-UNNAMED -XX:NativeMemoryTracking=summary -Xms256m -Xmx256m"

// Fixed lane (4.0.2): flat-footprint control.
tasks.register("writeLaneSpecFixed") {
    group = "pi4j-bench"
    description = "Emit build/lanes/memory-fixed.properties (pi4j $fixedVer, ofConfined)."
    val cp = files(tasks.named("jar"), configurations.named("runtimeClasspath"))
    inputs.files(cp)
    val outFile = layout.buildDirectory.file("lanes/memory-fixed.properties")
    val workingDir = layout.projectDirectory.asFile
    val jvmArgs = memJvmArgs
    val ver = fixedVer
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("type=main")
                appendLine("mainClass=com.pi4j.bench.memory.MemoryRunner")
                appendLine("workingDir=${workingDir.absolutePath}")
                appendLine("jvmArgs=$jvmArgs")
                appendLine("sysProps=pi4j.version=$ver")
                appendLine("args=--mode=fixed")
                appendLine("classpath=${cp.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }}")
            },
        )
    }
}

// Pre-fix lane (4.0.1): same bytecode, 4.0.1-only classpath → the #628 ramp.
tasks.register("writeLaneSpecIssue628") {
    group = "pi4j-bench"
    description = "Emit build/lanes/memory-issue628.properties (pre-fix pi4j $bugVer, ofAuto)."
    val cp = files(tasks.named("jar"), issue628)
    inputs.files(cp)
    val outFile = layout.buildDirectory.file("lanes/memory-issue628.properties")
    val workingDir = layout.projectDirectory.asFile
    val jvmArgs = memJvmArgs
    val ver = bugVer
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            buildString {
                appendLine("type=main")
                appendLine("mainClass=com.pi4j.bench.memory.MemoryRunner")
                appendLine("workingDir=${workingDir.absolutePath}")
                appendLine("jvmArgs=$jvmArgs")
                appendLine("sysProps=pi4j.version=$ver")
                appendLine("args=--mode=issue628")
                appendLine("classpath=${cp.files.joinToString(System.getProperty("path.separator")) { it.absolutePath }}")
            },
        )
    }
}

tasks.register("writeLaneSpec") {
    group = "pi4j-bench"
    description = "Emit both Block D lane specs (fixed + #628)."
    dependsOn("writeLaneSpecFixed", "writeLaneSpecIssue628")
}
