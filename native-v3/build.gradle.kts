// §5.4 — build the missing V3 JNI natives with THIS machine's toolchain, so the
// gpiod (JNA/JNI) AND linuxfs (JNI ioctl) lanes run on amd64/riscv64, not just ARM.
//
// The official 3.0.2 jars bundle these .so only for armhf/aarch64. Both Makefiles are
// plain JNI wrappers built *in place* (no TARGET_DIR var), needing JAVA_HOME for the
// JNI headers. gpiod additionally links distro libgpiod **v1.x**; linuxfs links -lrt.
// bench-v3 points `-Dpi4j.library.path` at libs/<arch>/, so BOTH wrappers (and the
// underlying libgpiod.so) must be staged there.
val arch: String = System.getProperty("os.arch")
val v3Tag = providers.gradleProperty("v3Tag").getOrElse("3.0.2")
val jdkHome: String = System.getProperty("java.home")

val v3SrcDir = layout.buildDirectory.dir("v3-src")
val outDir = layout.buildDirectory.dir("libs/$arch")
val v3SrcFile = v3SrcDir.get().asFile
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
