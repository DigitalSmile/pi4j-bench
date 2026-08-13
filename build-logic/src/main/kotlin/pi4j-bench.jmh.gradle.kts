// JMH-for-Gradle wiring: me.champeau.jmh + JSON results + browsable HTML report.
// Each consuming subproject gets its own `src/jmh/java` source set and a `jmh` task.
plugins {
    id("pi4j-bench.java")
    id("me.champeau.jmh")
    id("io.morethan.jmhreport")
}

val jmhResultJson = layout.buildDirectory.file("results/jmh/result.json")

jmh {
    jmhVersion = "1.37"
    resultFormat = "JSON"
    resultsFile = jmhResultJson
    // Slides demand 3+ forks; annotations set the real values, this is a floor.
    fork = 3
    warmupForks = 0
    failOnError = true
    jvmArgsAppend = listOf("--enable-native-access=ALL-UNNAMED", "-Xms512m", "-Xmx512m")
    // Optional GC profiler lane (Block D B/op) toggled via -PjmhProfilers=gc.
    (project.findProperty("jmhProfilers") as String?)?.let {
        profilers = it.split(",").map(String::trim).filter(String::isNotEmpty)
    }
}

val jmhReportDir = layout.buildDirectory.dir("reports/jmh")

jmhReport {
    jmhResultPath = jmhResultJson.get().asFile.path
    jmhReportOutput = jmhReportDir.get().asFile.path
}

tasks.named("jmh") {
    finalizedBy("jmhReport")
}

// io.morethan.jmhreport 0.9.6 (last release) invokes Task.project at execution
// time, which the configuration cache forbids. Opt this one task out so the graph
// still runs; JMH itself forks its own JVMs so it doesn't rely on the cache.
tasks.named("jmhReport") {
    notCompatibleWithConfigurationCache(
        "io.morethan.jmhreport 0.9.6 accesses Task.project at execution time")
    // The plugin copies bundled web assets (fonts/css) but never mkdirs its output.
    val out = jmhReportDir.get().asFile
    doFirst { out.mkdirs() }
}
