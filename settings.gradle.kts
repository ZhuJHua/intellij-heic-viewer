rootProject.name = "heic-viewer"

plugins {
    // Downloads a JDK 25 toolchain when none is installed (CI without setup-java, fresh machines).
    // Settings plugins cannot use the version catalog, so the version lives here.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
