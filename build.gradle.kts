plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Published modules derive their coordinates from GROUP / VERSION_NAME in
// gradle.properties (consumed by the maven-publish plugin). The values below keep
// the demo modules (which are not published) on the same group + version.
allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    // AGP-bundled Dokka (ASM8) can't parse Kotlin 2.3.10 bytecode (PermittedSubclasses -> ASM9).
    // Skip javadoc generation and publish an empty javadoc jar; Central only requires the artifact.
    tasks.matching { it.name == "javaDocReleaseGeneration" }.configureEach { enabled = false }
}
