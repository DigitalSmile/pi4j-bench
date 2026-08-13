// Result-dir layout + env-manifest stamping. Every result set is traceable to the
// exact machine/JDK/git SHA it was produced on; results/ is append-only.
import java.io.ByteArrayOutputStream

val arch: String = System.getProperty("os.arch")
val lane: String = (project.findProperty("lane") as String?) ?: "mock"

// results/<arch>/<lane>/  — the append-only root for this machine+lane.
val resultsRoot: Provider<Directory> =
    rootProject.layout.projectDirectory.dir("results").dir(arch).dir(lane).let { provider { it } }

// git SHA captured config-cache-safe via the provider API.
val gitSha: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "nogit" } }

tasks.register("stampEnvManifest") {
    group = "pi4j-bench"
    description = "Writes an environment manifest (arch, JDK, git SHA, lane) next to results."
    val outFile = resultsRoot.map { it.file("env-manifest.json") }
    val sha = gitSha
    val javaVersion = providers.systemProperty("java.version")
    val osName = providers.systemProperty("os.name")
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            {
              "arch": "$arch",
              "lane": "$lane",
              "gitSha": "${sha.get()}",
              "jdk": "${javaVersion.get()}",
              "os": "${osName.get()}",
              "project": "${project.name}"
            }
            """.trimIndent() + "\n"
        )
        logger.lifecycle("env manifest → ${f.relativeTo(rootProject.projectDir)}")
    }
}
