// Pi4J V4 (FFM) lane. Same FQCN+method names as bench-v3 → merged charts line up.
plugins {
    id("pi4j-bench.jmh")
    id("pi4j-bench.results")
}

dependencies {
    jmh(project(":bench-common"))
    jmh(libs.pi4j.v4.core)
    jmh(libs.pi4j.v4.ffm)
    jmh(libs.jmh.core)
    jmh(libs.junit.jupiter.api) // BaseSetup uses Assertions.fail on setup/teardown failure
    jmhAnnotationProcessor(libs.jmh.annprocess)
}
