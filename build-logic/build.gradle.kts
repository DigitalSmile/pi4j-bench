plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// Third-party Gradle plugins that our convention plugins apply must be on the
// build-logic classpath so `id("...")` resolves inside precompiled script plugins.
dependencies {
    implementation(libs.plugin.jmh)
    implementation(libs.plugin.jmhreport)
}
