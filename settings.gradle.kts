rootProject.name = "heic-viewer"

plugins {
    // Downloads the JDK toolchains that are not installed.
    // Settings plugins cannot use the version catalog.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
