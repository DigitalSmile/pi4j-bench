// The Java orchestrator — this module's main() IS `benchAll`. Gradle's only job here is
// to resolve each lane's classpath (writeLaneSpec, collected below) and produce the runner
// in two shapes: `:bench-runner:run` (dev) and a self-contained `bench-runner-all.jar`
// (one artifact, every arch, needs a JVM). All sequencing/env/mock/merge logic lives in
// com.pi4j.bench.runner. Deps: bench-common + picocli only — the v3/v4 pi4j-core GAVs are
// never on this classpath (the runner forks each lane instead).
import java.io.File

plugins {
    application
}

dependencies {
    implementation(project(":bench-common"))
    implementation(libs.picocli)
}

application {
    mainClass = "com.pi4j.bench.runner.BenchRunner"
}

// Embed the mock kernel-driver sources (C + Makefiles + setup/clean scripts, both flavours,
// minus the per-kernel .ko) into the artifact so a bare binary can materialise + build them
// next to itself (MockBuilder). INDEX lists every embedded file so extraction works without
// enumerating classpath directories (not reliably possible from inside a jar).
val mockResRoot = layout.buildDirectory.dir("generated/mockResources")
val embedMocks = tasks.register<Copy>("embedMocks") {
    group = "pi4j-bench"
    description = "Copy the mock driver sources into the artifact's resources (+ an INDEX)."
    into(mockResRoot.map { it.dir("mocks") })
    from(project(":bench-v4").layout.projectDirectory.dir("src/test")) { into("v4/src/test"); exclude("**/*.ko") }
    from(project(":bench-v3").layout.projectDirectory.dir("src/test")) { into("v3/src/test"); exclude("**/*.ko") }
    val outDir = mockResRoot
    doLast {
        val base = outDir.get().dir("mocks").asFile
        val lines = base.walkTopDown().filter { it.isFile && it.name != "INDEX" }
            .map { "mocks/" + it.relativeTo(base).invariantSeparatorsPath }
            .sorted().toList()
        base.resolve("INDEX").writeText(lines.joinToString("\n") + "\n")
    }
}
sourceSets["main"].resources.srcDir(mockResRoot)
tasks.named("processResources") { dependsOn(embedMocks) }

// --- lane specs → collected for the dev `run` -------------------------------------------
val laneSpecTasks = listOf(
    ":bench-v4:writeLaneSpec",
    ":bench-v3:writeLaneSpec",
    ":bench-latency:writeLaneSpec",
    ":bench-memory:writeLaneSpec",
    ":bench-warmup:writeLaneSpec", // FFM warmup lane (baseline; AOT/Leyden variants orchestrated)
    ":bench-ffm:writeLaneSpec",    // Block E deep-dive lanes (ffm-vthreads/-safepoint/-cleaner)
    ":native-image:writeLaneSpec", // NATIVE (opt-in) latency lane spec
)
val lanesDir = rootProject.layout.buildDirectory.dir("lanes")
val laneModules = listOf("bench-v4", "bench-v3", "bench-latency", "bench-memory", "bench-warmup", "bench-ffm", "native-image")

val collectLaneSpecs = tasks.register<Copy>("collectLaneSpecs") {
    group = "pi4j-bench"
    description = "Gather every lane's build/lanes/*.properties into the root build/lanes dir."
    dependsOn(laneSpecTasks)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    laneModules.forEach { from(project(":$it").layout.buildDirectory.dir("lanes")) }
    into(lanesDir)
}

tasks.named<JavaExec>("run") {
    dependsOn(collectLaneSpecs)
    // Run from the repo root so results/<arch>/<lane>/ and the mock setup scripts resolve
    // exactly as the old Gradle tasks did.
    workingDir = rootProject.projectDir
    systemProperty("bench.lanesDir", lanesDir.get().asFile.absolutePath)
    systemProperty("bench.root", rootProject.projectDir.absolutePath)
    // Stamped into run-manifest.json by ReportMerger (the runner has no version catalog).
    systemProperty("pi4j.v4", libs.versions.pi4jV4.get())
    systemProperty("pi4j.v3", libs.versions.pi4jV3.get())
}

// --- self-contained fat jar (hand-rolled — no Shadow plugin) ----------------------------
// One artifact for every arch (needs a JVM). Our deps have single service providers, so a
// plain merge with duplicatesStrategy=EXCLUDE is safe; Multi-Release honors jackson/oshi.
val fatJar = tasks.register<Jar>("fatJar") {
    group = "pi4j-bench"
    description = "Build the self-contained bench-runner-all.jar (java -jar …)."
    archiveBaseName = "bench-runner"
    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "com.pi4j.bench.runner.BenchRunner",
            "Multi-Release" to "true",
        )
    }
    dependsOn(configurations.named("runtimeClasspath")) // build dependency jars first (validation-clean)
    from(sourceSets["main"].output)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    // Running on the classpath (not the module path) → drop module descriptors + any
    // upstream signatures so the merged jar stays loadable.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
}

// --- scp-ready bundle -------------------------------------------------------------------
// Stages the orchestrator fat jar + every lane's jars (deduped) with paths relative to the
// bundle root, plus the mock script/native trees the JMH lanes need and a per-board native
// builder. Copy the whole build/bundle/ to a board and run ./bench (needs a JDK for both the
// launcher and the forked lanes; V3 gpiod natives + mock .ko are built on the target via
// build-natives.sh).
val bundleDir = layout.buildDirectory.dir("bundle")

val bundle = tasks.register("bundle") {
    group = "pi4j-bench"
    description = "Assemble a self-contained, scp-ready benchmark bundle in build/bundle/."
    // buildAll cross-compiles the V3 natives for every arch a toolchain is present for, so
    // the bundle can carry all-platform .so (not just the host's).
    dependsOn(collectLaneSpecs, fatJar, ":native-v3:buildAll")

    val specsDir = lanesDir
    val out = bundleDir
    val fatJarFile = fatJar.flatMap { it.archiveFile }
    val archLocal = System.getProperty("os.arch") // the host that assembled this bundle
    val v4Version = libs.versions.pi4jV4.get()
    val v3Version = libs.versions.pi4jV3.get()
    val configTemplate = layout.projectDirectory.file("src/main/resources/bench.config").asFile
    val rootDirFile = rootProject.projectDir // captured (referencing rootProject in doLast breaks CC)
    // Every arch's V3 JNI natives (build/libs/<arch>/*.so), staged under lanes/natives/<arch>.
    val nativeLibsRoot = project(":native-v3").layout.buildDirectory.dir("libs").get().asFile
    // The opt-in GraalVM AOT latency binary — staged under lanes/native/<arch> when built.
    val nativeImageBin = project(":native-image").layout.buildDirectory
        .file("native/nativeCompile/latency-v4-native").get().asFile

    inputs.dir(specsDir)
    inputs.file(fatJarFile)
    inputs.file(configTemplate)
    outputs.dir(out)

    doLast {
        val sep = System.getProperty("path.separator")
        val bundleRoot = out.get().asFile
        val libs = bundleRoot.resolve("lanes/libs")
        bundleRoot.deleteRecursively()
        libs.mkdirs()

        // 1) Orchestrator fat jar + the runtime config template.
        fatJarFile.get().asFile.copyTo(bundleRoot.resolve("bench-runner-all.jar"), overwrite = true)
        configTemplate.copyTo(bundleRoot.resolve("bench.config"), overwrite = true)

        // 2) Lane specs: stage every referenced jar into lanes/libs (deduped by name) and
        //    rewrite each spec with bundle-relative paths.
        specsDir.get().asFile.listFiles { f -> f.name.endsWith(".properties") }?.sortedBy { it.name }?.forEach { specFile ->
            val name = specFile.name.removeSuffix(".properties")
            val kv = linkedMapOf<String, String>()
            specFile.readLines().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) kv[line.substring(0, i)] = line.substring(i + 1)
            }

            kv["classpath"] = (kv["classpath"] ?: "").split(sep).filter { it.isNotBlank() }.joinToString(sep) { entry ->
                val jar = File(entry)
                jar.copyTo(libs.resolve(jar.name), overwrite = true)
                "lanes/libs/${jar.name}"
            }

            // Working dir is resolved at run time: JMH lanes get the materialised mock tree
            // (MockBuilder → mocks/<flavor>), everything else the bundle root.
            kv["workingDir"] = "."

            // Rebase the V3 native dir onto a RUNTIME-resolved path: the runner expands the
            // ${bench.arch} token to the arch it's actually running on (LaneSpec), so one
            // multi-arch bundle picks the right lanes/natives/<arch> on any board. The .so
            // for every arch are staged once, below (step 3).
            kv["sysProps"]?.let { sp ->
                if (sp.contains("pi4j.library.path=")) {
                    // escapeReplacement keeps the literal ${bench.arch} token — the runner
                    // expands it per-board — instead of it being read as a regex group ref.
                    kv["sysProps"] = sp.replace(
                        Regex("pi4j\\.library\\.path=[^;]+"),
                        Regex.escapeReplacement("pi4j.library.path=lanes/natives/\${bench.arch}"))
                }
            }

            // NATIVE lane: point the binary at the per-board staged path (arch expanded by
            // the runner). The binary itself is staged once, below, when it was built.
            kv["binary"]?.let {
                kv["binary"] = "lanes/native/\${bench.arch}/${File(it).name}"
            }

            val target = bundleRoot.resolve("lanes/$name.properties")
            target.parentFile.mkdirs()
            target.writeText(kv.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }

        // 3) V3 JNI natives for EVERY arch buildAll produced — staged under
        //    lanes/natives/<arch>/ so the same bundle runs the V3 lane on amd64/arm64/riscv64.
        //    The runner picks its own arch's dir at run time via the ${bench.arch} token above.
        val stagedArches = mutableListOf<String>()
        nativeLibsRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { archDir ->
            val sos = archDir.listFiles { f -> f.name.endsWith(".so") }?.sortedBy { it.name } ?: emptyList()
            if (sos.isNotEmpty()) {
                val nativesOut = bundleRoot.resolve("lanes/natives/${archDir.name}").apply { mkdirs() }
                sos.forEach { it.copyTo(nativesOut.resolve(it.name), overwrite = true) }
                stagedArches += "${archDir.name}(${sos.size})"
            }
        }
        logger.lifecycle("bundle: staged V3 natives for ${if (stagedArches.isEmpty()) "no arch" else stagedArches.joinToString(", ")}")

        // 3b) GraalVM AOT latency binary (opt-in), if it was built for this host arch.
        if (nativeImageBin.exists()) {
            val nativeOut = bundleRoot.resolve("lanes/native/$archLocal").apply { mkdirs() }
            nativeImageBin.copyTo(nativeOut.resolve(nativeImageBin.name), overwrite = true).setExecutable(true)
            logger.lifecycle("bundle: staged native-image latency binary for $archLocal")
        }

        // Mock drivers are embedded in the artifact (see :embedMocks) and materialised +
        // built next to the binary on first run (MockBuilder) — nothing to stage here.

        // 4) Launcher — runs the fat jar. Needs a JDK 25 on PATH (also used for the forked
        //    lanes; set `jdk` in bench.config to point at a specific one).
        bundleRoot.resolve("bench").writeText(
            """
            #!/usr/bin/env bash
            # Self-contained bench launcher (fat jar; needs a JDK 25).
            set -euo pipefail
            HERE="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            exec java -jar "${'$'}HERE/bench-runner-all.jar" --bundle-root "${'$'}HERE" "${'$'}@"
            """.trimIndent() + "\n",
        )
        bundleRoot.resolve("bench").setExecutable(true)

        // 5) Per-board native builder — mock .ko + V3 gpiod/linuxfs .so (arch-specific). The
        //    bundle already ships cross-compiled .so for every arch a toolchain was present
        //    for at build time (step 3), so this only matters when the RUNNING arch wasn't one
        //    of them (e.g. no libgpiod-dev:<arch> / gcc-<triplet> on the build host).
        bundleRoot.resolve("build-natives.sh").writeText(
            """
            #!/usr/bin/env bash
            # Per-board natives. The mock kernel modules build automatically on the first
            # `./bench` run (MockBuilder materialises the embedded sources next to the binary
            # and compiles each .ko once, reused after). The V3 gpiod/linuxfs JNI .so are
            # arch-specific and NOT embedded (see plan §5.4 / native-v3); the bundle ships
            # cross-compiled .so for every arch it could build. Pure-Java lanes (V4/FFM,
            # linuxfs, JMH, memory) need nothing. Run this only if the V3 lane's .so are
            # missing for THIS arch (i.e. the build host lacked its cross toolchain).
            set -euo pipefail
            HERE="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            case "${'$'}(uname -m)" in
                x86_64|amd64) ARCH=amd64 ;;
                aarch64|arm64) ARCH=arm64 ;;
                *) ARCH="${'$'}(uname -m)" ;;
            esac
            DIR="${'$'}HERE/lanes/natives/${'$'}ARCH"
            mkdir -p "${'$'}DIR"
            if compgen -G "${'$'}DIR/*.so" > /dev/null; then
                echo "build-natives: V3 .so already staged in ${'$'}DIR — nothing to do."
            else
                echo "build-natives: no V3 .so for ${'$'}ARCH; build them (native-v3, plan §5.4)"
                echo "               and stage libgpiod.so/libpi4j-gpiod.so/libpi4j-linuxfs.so into ${'$'}DIR/."
            fi
            """.trimIndent() + "\n",
        )
        bundleRoot.resolve("build-natives.sh").setExecutable(true)

        // 6) A short read-me stamped with the versions this bundle was built against.
        bundleRoot.resolve("BUNDLE.md").writeText(
            """
            # pi4j-bench bundle (assembled on: $archLocal)

            Self-contained benchmark orchestrator. Pi4J V4=$v4Version, V3=$v3Version.
            Ships cross-compiled V3 natives under `lanes/natives/<arch>/`; `./bench` picks the
            running board's arch automatically.

            ## Run
                ./bench --lane mock --quick          # smoke test
                ./bench --lane hw                    # real peripherals
                ./bench --dry-run                    # print the plan, change nothing

            `bench` runs `java -jar bench-runner-all.jar`, so it needs a JDK 25 (also used
            for the forked measurement lanes): keep one on PATH, or set
            `jdk = /path/to/jdk-25` in bench.config.

            ## Config (bench.config)
            Edit `bench.config` to set the JDK for forks and the mock name/address map
            (i2c bus/device, gpio chip/line, verification nodes). Passed automatically;
            override the file with `./bench --config /other/bench.config`.

            ## Mocks & per-board setup
            The mock kernel-driver SOURCES are embedded in the binary. On the first
            `./bench --lane mock` run they're materialised into `mocks/` next to the binary
            and each `.ko` is compiled once (reused after; `--rebuild-mocks` forces a rebuild).
            insmod/rmmod need root — the runner prints the exact NOPASSWD `sudoers` line and
            writes `sudoers.snippet`; add it once with `sudo visudo`.

            The V3 gpiod/linuxfs JNI `.so` are arch-specific and not embedded, but the bundle
            ships cross-compiled copies for every arch it could build (`lanes/natives/<arch>/`).
            If your board's arch is missing, `./build-natives.sh` says so; build them via
            native-v3 (plan §5.4). Pure-Java lanes need nothing.
            """.trimIndent() + "\n",
        )

        logger.lifecycle("bundle → ${bundleRoot.relativeTo(rootDirFile)} " +
            "(fat jar + ${specsDir.get().asFile.listFiles()?.size ?: 0} lane specs)")
    }
}

// The fat-jar bundle is arch-agnostic: it runs on amd64/arm64/riscv64 as-is (needs a JDK 25).

// Opt-in: build the GraalVM AOT latency binary for THIS host arch, then fold it into the
// bundle (staged under lanes/native/<arch>/). Needs a GraalVM 25 toolchain. Run the native
// lane with `./bench --native …`.
tasks.register("nativeBundle") {
    group = "pi4j-bench"
    description = "Build the native-image AOT latency binary (host arch) and bundle it in (needs GraalVM)."
    dependsOn(":native-image:nativeCompile")
    finalizedBy("bundle")
}
tasks.named("bundle") { mustRunAfter(":native-image:nativeCompile") }
