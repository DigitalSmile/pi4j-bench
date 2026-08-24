// Root = a thin aggregator. Orchestration used to live here as a fragile task graph
// (envCheck → loadMocks → measure → merge → unload, hand-sequenced with mustRunAfter /
// finalizedBy and config-cache opt-outs). It now lives in the `bench-runner` module's
// main() — Gradle's job shrank to resolving each lane's classpath and launching that one
// JVM. See :bench-runner and plan.md §4.3.

group = "io.github.digitalsmith"
version = "1.0-SNAPSHOT"

// Shared Java convention for every module — JDK 25 toolchain, UTF-8, FFM native access,
// lint. This used to be a `pi4j-bench.java` precompiled-script-plugin in an included
// `build-logic` build; for a single 30-line convention that whole extra build was overkill,
// so it now lives here as one `subprojects {}` block (no included build, no plugin id to
// apply in each module). `native-v3` is native-only (Exec tasks, its own `build` task, zero
// Java sources), so it's excluded — applying the `java` plugin there would clash.
subprojects {
    if (name == "native-v3") return@subprojects
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        // Any JDK 25 vendor; local installs discovered via
        // org.gradle.java.installations.paths (gradle.properties).
        toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
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
}

// `./gradlew benchAll` runs the whole matrix with defaults (--lane mock). To pass options,
// invoke the runner directly so Gradle forwards --args to its JavaExec:
//   ./gradlew :bench-runner:run --args="--lane mock --quick"
//   ./gradlew :bench-runner:run --args="--lane hw --gc zgc --jfr"
//   ./gradlew :bench-runner:run --args="--lane mock --dry-run"
tasks.register("benchAll") {
    group = "pi4j-bench"
    description = "Run the whole benchmark matrix via the Java orchestrator (:bench-runner:run)."
    dependsOn(":bench-runner:run")
}
