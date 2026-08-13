// Pi4J V3 lane (gpiod / linuxfs / pigpio). Mirrors bench-v4 class+method names.
plugins {
    id("pi4j-bench.jmh")
    id("pi4j-bench.results")
}

val arch: String = System.getProperty("os.arch")

dependencies {
    jmh(project(":bench-common"))
    jmh(libs.pi4j.v3.core)
    jmh(libs.pi4j.v3.gpiod)
    jmh(libs.pi4j.v3.linuxfs)
    jmh(libs.pi4j.v3.pigpio)
    jmh(libs.jmh.core)
    jmh(libs.junit.jupiter.api) // BaseSetup uses Assertions.fail on setup/teardown failure
    jmhAnnotationProcessor(libs.jmh.annprocess)
}

// The "провязка": both V3 gpiod AND linuxfs need JNI natives that the official jars
// ship only for ARM. Build them locally (§5.4) and point the Pi4J v2/v3 loader at the
// staged dir — a single `pi4j.library.path` covers every V3 native (gpiod wrapper +
// underlying libgpiod + linuxfs wrapper).
val v3NativeDir = project(":native-v3").layout.buildDirectory.dir("libs/$arch")

jmh {
    // Pi4J v2/v3 native loader honors this → skips the (absent) in-jar lib.
    jvmArgsAppend.add(v3NativeDir.map { "-Dpi4j.library.path=${it.asFile.path}" })
}

tasks.named("jmh") {
    dependsOn(":native-v3:build") // builds libpi4j-gpiod.so + libgpiod.so + libpi4j-linuxfs.so
}
