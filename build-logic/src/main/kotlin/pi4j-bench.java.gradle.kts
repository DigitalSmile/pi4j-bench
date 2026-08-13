// JDK 25 toolchain, UTF-8, native-access for FFM, shared across every subproject.
plugins {
    java
}

java {
    toolchain {
        // Any JDK 25 vendor; local installs are discovered via
        // org.gradle.java.installations.paths (see gradle.properties).
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // JDK 25 finalized features (JEP 511/512/513/506) need no --enable-preview.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Pi4J V4 FFM downcalls require native access to be granted explicitly.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
