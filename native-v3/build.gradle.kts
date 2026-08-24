// §5.4 — build the missing V3 JNI natives with THIS machine's toolchain, so the
// gpiod (JNA/JNI) AND linuxfs (JNI ioctl) lanes run on amd64/riscv64, not just ARM.
//
// The official 3.0.2 jars bundle these .so only for armhf/aarch64. Both Makefiles are
// plain JNI wrappers built *in place* (no TARGET_DIR var), needing JAVA_HOME for the
// JNI headers. gpiod additionally links distro libgpiod **v1.x**; linuxfs links -lrt.
// bench-v3 points `-Dpi4j.library.path` at libs/<arch>/, so BOTH wrappers (and the
// underlying libgpiod.so) must be staged there.
//
// `build` compiles for THIS host only (dev runs / writeLaneSpec). `buildAll` additionally
// CROSS-compiles for every arch a toolchain is present for, so `:bench-runner:bundle` can
// ship all-platform natives from one host — the Makefiles already honour `CROSS_PREFIX`, so
// each non-host arch just needs `gcc-<triplet>` + the multiarch `libgpiod-dev:<arch>`
// installed beforehand. Arches whose toolchain/lib is missing are skipped with a warning.
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject

val arch: String = System.getProperty("os.arch")
val v3Tag = providers.gradleProperty("v3Tag").getOrElse("3.0.2")
val jdkHome: String = System.getProperty("java.home")
// libgpiod v1.x — the API the 3.0.2 gpiod JNI wrapper targets. Built FROM SOURCE per arch
// (buildAll) so the wrapper links a known libgpiod on every arch, with no reliance on the
// host carrying a multiarch `libgpiod-dev:<arch>` (why arm64 was previously skipped).
val libgpiodTag = providers.gradleProperty("libgpiodTag").getOrElse("v1.6.5")

val v3SrcDir = layout.buildDirectory.dir("v3-src")
val libgpiodSrcDir = layout.buildDirectory.dir("libgpiod-src")
val outDir = layout.buildDirectory.dir("libs/$arch")
val v3SrcFile = v3SrcDir.get().asFile
val libgpiodSrcFile = libgpiodSrcDir.get().asFile
val outDirFile = outDir.get().asFile

val cloneV3 by tasks.registering(Exec::class) {
    group = "native-v3"
    description = "Shallow-clone the pinned Pi4J tag ($v3Tag) that still ships the JNI wrappers."
    notCompatibleWithConfigurationCache("runs git clone; captures no reusable state")
    commandLine(
        "git", "clone", "--depth", "1", "--branch", v3Tag,
        "https://github.com/Pi4J/pi4j.git", v3SrcFile.path,
    )
    onlyIf { !v3SrcFile.exists() }
    outputs.dir(v3SrcDir)
}

val cloneLibgpiod by tasks.registering(Exec::class) {
    group = "native-v3"
    description = "Shallow-clone libgpiod $libgpiodTag (v1 API) to cross-build from source."
    notCompatibleWithConfigurationCache("runs git clone; captures no reusable state")
    // The v1.x tags live on the kernel.org canonical repo (the GitHub mirror carries v2+ only).
    commandLine(
        "git", "clone", "--depth", "1", "--branch", libgpiodTag,
        "https://git.kernel.org/pub/scm/libs/libgpiod/libgpiod.git", libgpiodSrcFile.path,
    )
    onlyIf { !libgpiodSrcFile.exists() }
    outputs.dir(libgpiodSrcDir)
}

val gpiodSubdir = "libraries/pi4j-library-gpiod/src/main/native"
val linuxfsSubdir = "libraries/pi4j-library-linuxfs/src/main/native"
val gpiodDirFile = v3SrcDir.get().dir(gpiodSubdir).asFile
val linuxfsDirFile = v3SrcDir.get().dir(linuxfsSubdir).asFile

val buildV3Gpiod by tasks.registering(Exec::class) {
    group = "native-v3"
    description = "Compile libpi4j-gpiod.so against distro libgpiod v1.x (plain gcc, -lgpiod -lrt)."
    notCompatibleWithConfigurationCache("runs make against the distro toolchain")
    dependsOn(cloneV3)
    workingDir(gpiodDirFile)
    environment("JAVA_HOME", jdkHome) // Makefile pulls JNI headers from $JAVA_HOME/include
    commandLine("make", "clean", "all")
    inputs.dir(v3SrcDir.map { it.dir(gpiodSubdir) })
    outputs.dir(outDir)
    val nativeDir = gpiodDirFile
    val out = outDirFile
    doLast {
        val so = nativeDir.resolve("libpi4j-gpiod.so")
        if (!so.exists()) throw GradleException("libpi4j-gpiod.so not produced — is libgpiod-dev (v1.x) installed?")
        out.mkdirs()
        so.copyTo(out.resolve("libpi4j-gpiod.so"), overwrite = true)

        // GpioD.<clinit> loads libgpiod.so BEFORE the wrapper, and with pi4j.library.path
        // set it System.load()s each by name from that dir. The official jar ships no
        // amd64 libgpiod, so stage the distro one (our wrapper NEEDs libgpiod.so.2 anyway).
        val sysGpiod = listOf("/usr/lib", "/lib", "/usr/local/lib")
            .map(::File).filter { it.isDirectory }
            .flatMap { runCatching { it.walkTopDown().maxDepth(2).toList() }.getOrDefault(emptyList()) }
            .filter { it.isFile && Regex("""libgpiod\.so(\.\d+)*""").matches(it.name) }
            .minByOrNull { it.name.length } // prefer libgpiod.so over libgpiod.so.2.x
            ?: throw GradleException("system libgpiod.so not found — install libgpiod-dev (v1.x)")
        sysGpiod.copyTo(out.resolve("libgpiod.so"), overwrite = true)

        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(so.readBytes()).joinToString("") { "%02x".format(it) }
        out.resolve("fingerprint-gpiod.json").writeText(
            """{"v3Tag":"$v3Tag","arch":"$arch","lib":"libpi4j-gpiod.so","sha256":"$sha",""" +
                """"libgpiod":"${sysGpiod.canonicalPath}"}""" + "\n")
    }
}

val buildV3Linuxfs by tasks.registering(Exec::class) {
    group = "native-v3"
    description = "Compile libpi4j-linuxfs.so (JNI ioctl wrapper; plain gcc, -lrt, no external deps)."
    notCompatibleWithConfigurationCache("runs make against the distro toolchain")
    dependsOn(cloneV3)
    workingDir(linuxfsDirFile)
    environment("JAVA_HOME", jdkHome)
    commandLine("make", "clean", "all")
    inputs.dir(v3SrcDir.map { it.dir(linuxfsSubdir) })
    outputs.dir(outDir)
    val nativeDir = linuxfsDirFile
    val out = outDirFile
    doLast {
        val so = nativeDir.resolve("libpi4j-linuxfs.so")
        if (!so.exists()) throw GradleException("libpi4j-linuxfs.so not produced (make failed?)")
        out.mkdirs()
        so.copyTo(out.resolve("libpi4j-linuxfs.so"), overwrite = true)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(so.readBytes()).joinToString("") { "%02x".format(it) }
        out.resolve("fingerprint-linuxfs.json").writeText(
            """{"v3Tag":"$v3Tag","arch":"$arch","lib":"libpi4j-linuxfs.so","sha256":"$sha"}""" + "\n")
    }
}

tasks.register("build") {
    description = "Build all V3 JNI natives (gpiod + linuxfs) for this arch."
    dependsOn(buildV3Gpiod, buildV3Linuxfs)
}

// --- all-platform cross build -----------------------------------------------------------
// The bundle wants natives for every arch. Each target maps to a Debian multiarch triplet;
// the host arch builds with plain gcc (empty CROSS_PREFIX), the rest with gcc-<triplet>.
data class NativeTarget(val label: String, val triplet: String)
val nativeTargets = listOf(
    NativeTarget("amd64", "x86_64-linux-gnu"),
    NativeTarget("arm64", "aarch64-linux-gnu"),
    NativeTarget("riscv64", "riscv64-linux-gnu"),
)
val hostArchLabel = when (arch) {
    "amd64", "x86_64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> arch
}

/// Cross-compiles both JNI wrappers for every target arch whose toolchain is installed,
/// staging each into build/libs/<label>/ (host arch uses plain gcc). ExecOperations is
/// injected so the loop runs one make per (arch, lib) without a task explosion.
abstract class BuildV3NativesAll @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    // All @Internal: a native cross build has no reliable up-to-date signal (system
    // toolchains/libs change out of band), so the task always runs when invoked.
    @get:Internal abstract val hostArch: Property<String>
    @get:Internal abstract val jdk: Property<String>
    @get:Internal abstract val tag: Property<String>
    @get:Internal abstract val gpiodTag: Property<String>
    @get:Internal abstract val targets: ListProperty<Pair<String, String>>
    @get:Internal abstract val gpiodDir: DirectoryProperty
    @get:Internal abstract val linuxfsDir: DirectoryProperty
    @get:Internal abstract val libgpiodSrc: DirectoryProperty
    @get:Internal abstract val libsRoot: DirectoryProperty

    @TaskAction
    fun run() {
        val staged = linkedMapOf<String, MutableList<String>>()
        for ((label, triplet) in targets.get()) {
            val crossPrefix = if (label == hostArch.get()) "" else "$triplet-"
            val libs = mutableListOf<String>()

            // A non-host arch needs its cross gcc; skip the whole arch cleanly if absent.
            if (crossPrefix.isNotEmpty() && !hasCommand("${crossPrefix}gcc")) {
                logger.warn("native-v3: skip $label — ${crossPrefix}gcc not on PATH (install gcc-$triplet)")
                staged[label] = libs
                continue
            }
            val outDir = libsRoot.get().dir(label).asFile.apply { mkdirs() }

            // gpiod: build libgpiod v1 FROM SOURCE for this arch and link the wrapper against
            // it — no reliance on a distro `libgpiod-dev:<arch>` (why arm64 used to be skipped).
            // If the from-source build can't complete on this host, fall back to a distro lib.
            val gpiodPrefix = buildLibgpiod(label, triplet, crossPrefix)
            val gpiodArgs = if (gpiodPrefix != null) {
                val inc = "-I. -I${jdk.get()}/include -I${jdk.get()}/include/linux -I${gpiodPrefix.resolve("include")}"
                val lib = "-L${gpiodPrefix.resolve("lib")} -Wl,-rpath-link,${gpiodPrefix.resolve("lib")} -lgpiod -lrt"
                listOf("INCLUDE=$inc", "LIBS=$lib")
            } else {
                emptyList()
            }
            if (make(gpiodDir.get().asFile, crossPrefix, "$label/gpiod", gpiodArgs)) {
                copyOut(gpiodDir.get().asFile, "libpi4j-gpiod.so", outDir)
                val libgpiodSo = gpiodPrefix?.let { firstLibgpiod(it.resolve("lib")) }
                    ?: findLibgpiod(label == hostArch.get(), triplet)
                if (libgpiodSo != null) {
                    libgpiodSo.copyTo(outDir.resolve("libgpiod.so"), overwrite = true)
                    val origin = if (gpiodPrefix != null) "source(${gpiodTag.get()})" else "distro"
                    fingerprint(outDir, "gpiod", label, "libpi4j-gpiod.so",
                        outDir.resolve("libpi4j-gpiod.so"), "$origin:${libgpiodSo.canonicalPath}")
                    libs += "gpiod"
                } else {
                    logger.warn("native-v3: $label gpiod built but no libgpiod.so to stage")
                }
            } else {
                logger.warn("native-v3: skip $label gpiod — build failed (libgpiod + wrapper for $triplet)")
            }

            // linuxfs: only -lrt, so it cross-builds wherever the cross gcc exists.
            if (make(linuxfsDir.get().asFile, crossPrefix, "$label/linuxfs")) {
                copyOut(linuxfsDir.get().asFile, "libpi4j-linuxfs.so", outDir)
                fingerprint(outDir, "linuxfs", label, "libpi4j-linuxfs.so",
                    outDir.resolve("libpi4j-linuxfs.so"), null)
                libs += "linuxfs"
            } else {
                logger.warn("native-v3: skip $label linuxfs — make failed")
            }
            staged[label] = libs
        }

        val summary = staged.entries.joinToString("  ") { (a, l) ->
            if (l.isEmpty()) "$a=none" else "$a=${l.joinToString("+")}"
        }
        logger.lifecycle("native-v3 buildAll → $summary")
        if (staged.values.all { it.isEmpty() }) {
            throw GradleException("native-v3: no arch produced any native — install a cross toolchain " +
                "(gcc-<triplet>) + libgpiod-dev:<arch>, or run :native-v3:build for the host arch only")
        }
    }

    private fun make(dir: java.io.File, crossPrefix: String, tag: String,
                     extraArgs: List<String> = emptyList()): Boolean {
        val out = ByteArrayOutputStream()
        val res = exec.exec {
            workingDir = dir
            environment("JAVA_HOME", jdk.get())
            // Command-line make vars override the Makefile's CC/INCLUDE/LIBS assignments.
            commandLine(listOf("make", "CROSS_PREFIX=$crossPrefix") + extraArgs + listOf("clean", "all"))
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        if (res.exitValue != 0) logger.info("native-v3 make[$tag] failed:\n$out")
        return res.exitValue == 0
    }

    /// Cross-builds libgpiod (v1) from source into build/libgpiod/<label>/{lib,include} and
    /// returns that prefix. Returns null (→ distro fallback) if autotools or the cross
    /// toolchain can't complete it, so buildAll never regresses on an under-provisioned host.
    private fun buildLibgpiod(label: String, triplet: String, crossPrefix: String): java.io.File? {
        val src = libgpiodSrc.get().asFile
        if (!src.isDirectory) return null
        val prefix = libsRoot.get().asFile.resolveSibling("libgpiod").resolve(label)
        val builtSo = prefix.resolve("lib/libgpiod.so")
        if (builtSo.exists()) return prefix // cached from a prior run

        // Generate ./configure once (in-tree, arch-independent).
        if (!src.resolve("configure").exists() && !sh(src, "autoreconf -fi")) {
            logger.warn("native-v3: libgpiod autoreconf failed (need autoconf/automake/libtool) — " +
                "$label falls back to distro libgpiod")
            return null
        }
        val buildDir = prefix.resolve("_build").apply { mkdirs() }
        val host = if (crossPrefix.isEmpty()) "" else "--host=$triplet"
        // CPPFLAGS=-I/usr/include exposes the arch-generic uapi linux/gpio.h to a cross build.
        val configure = src.resolve("configure").absolutePath
        val ok = sh(buildDir, "$configure $host --prefix=${prefix.absolutePath} " +
            "--enable-shared --disable-static --enable-tools=no --disable-bindings-cxx " +
            "CPPFLAGS=-I/usr/include") &&
            sh(buildDir, "make -j2") &&
            sh(buildDir, "make install")
        if (ok && builtSo.exists()) return prefix
        logger.warn("native-v3: libgpiod from-source build failed for $label — falling back to distro")
        return null
    }

    private fun sh(dir: java.io.File, cmd: String): Boolean {
        val out = ByteArrayOutputStream()
        val res = exec.exec {
            workingDir = dir
            commandLine("sh", "-c", cmd)
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        if (res.exitValue != 0) logger.info("native-v3 sh[$cmd] failed:\n$out")
        return res.exitValue == 0
    }

    /// The `libgpiod.so` (shortest-named) under a from-source `lib/` prefix.
    private fun firstLibgpiod(dir: java.io.File): java.io.File? =
        (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && Regex("""libgpiod\.so(\.\d+)*""").matches(it.name) }
            .minByOrNull { it.name.length }

    private fun copyOut(nativeDir: java.io.File, so: String, outDir: java.io.File) {
        val built = nativeDir.resolve(so)
        if (!built.exists()) throw GradleException("native-v3: make succeeded but $so missing in $nativeDir")
        built.copyTo(outDir.resolve(so), overwrite = true)
    }

    /// The runtime libgpiod.so for a target: the multiarch dir is authoritative for a cross
    /// arch; the host may also carry it in the generic lib dirs.
    private fun findLibgpiod(host: Boolean, triplet: String): java.io.File? {
        val dirs = if (host) listOf("/usr/lib/$triplet", "/usr/lib", "/lib", "/usr/local/lib")
        else listOf("/usr/lib/$triplet")
        return dirs.map { File(it) }.filter { it.isDirectory }
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && Regex("""libgpiod\.so(\.\d+)*""").matches(it.name) }
            .minByOrNull { it.name.length } // prefer libgpiod.so over libgpiod.so.2.x
    }

    private fun fingerprint(outDir: java.io.File, kind: String, label: String, lib: String,
                            so: java.io.File, libgpiod: String?) {
        val sha = MessageDigest.getInstance("SHA-256").digest(so.readBytes())
            .joinToString("") { "%02x".format(it) }
        val extra = if (libgpiod != null) ""","libgpiod":"$libgpiod"""" else ""
        outDir.resolve("fingerprint-$kind.json").writeText(
            """{"v3Tag":"${tag.get()}","arch":"$label","lib":"$lib","sha256":"$sha"$extra}""" + "\n")
    }

    private fun hasCommand(cmd: String): Boolean {
        val res = exec.exec {
            commandLine("sh", "-c", "command -v $cmd")
            isIgnoreExitValue = true
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
        return res.exitValue == 0
    }
}

tasks.register<BuildV3NativesAll>("buildAll") {
    group = "native-v3"
    description = "Cross-compile the V3 JNI natives for every arch with a toolchain (bundle: all platforms)."
    notCompatibleWithConfigurationCache("runs make against per-arch cross toolchains")
    dependsOn(cloneV3, cloneLibgpiod)
    hostArch.set(hostArchLabel)
    jdk.set(jdkHome)
    tag.set(v3Tag)
    gpiodTag.set(libgpiodTag)
    targets.set(nativeTargets.map { it.label to it.triplet })
    gpiodDir.set(layout.dir(provider { gpiodDirFile }))
    linuxfsDir.set(layout.dir(provider { linuxfsDirFile }))
    libgpiodSrc.set(layout.dir(provider { libgpiodSrcFile }))
    libsRoot.set(layout.buildDirectory.dir("libs"))
}
