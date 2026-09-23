import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import java.util.Properties

plugins {
    id("java") // Java support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Optional machine-specific settings (see README.md, "Development"): a Gradle property (-P..., ~/.gradle/gradle.properties)
// wins over the git-ignored local.properties in the project root; a blank value disables the setting.
//   platformLocalPath   installed IDE to compile against, run (runIde) and verify with, instead of downloading
//                       platformType/platformVersion; e.g. /Applications/Android Studio.app
//   platformCanaryPath  optional second installed IDE, used by runIdeCanary and (with platformLocalPath) verifyPlugin
//   jetbrainsSignDir    directory with chain.crt + private_encrypted.pem for local signing (default ~/.jetbrains-sign)
val localProperties = Properties().apply {
    providers.fileContents(layout.projectDirectory.file("local.properties")).asText.orNull?.let { load(it.reader()) }
}

fun localSetting(name: String): String? =
    (providers.gradleProperty(name).orNull ?: localProperties.getProperty(name))?.trim()?.takeIf { it.isNotEmpty() }

val platformLocalPath: File? = localSetting("platformLocalPath")?.let { path ->
    file(path).takeIf { it.isDirectory } ?: throw GradleException(
        "platformLocalPath '$path' does not exist. Fix it in local.properties, or pass -PplatformLocalPath= to use " +
            "${providers.gradleProperty("platformType").get()} ${providers.gradleProperty("platformVersion").get()} " +
            "from gradle.properties (downloaded once)."
    )
}
val platformCanaryPath: File? = localSetting("platformCanaryPath")?.let { path ->
    file(path).takeIf { it.isDirectory }.also {
        if (it == null) logger.warn("platformCanaryPath '$path' does not exist; runIdeCanary is disabled.")
    }
}

// Set the JVM language level used to build the project.
java {
    // FFM (java.lang.foreign) is final since Java 22; every supported IDE (since-build 261.26222) runs on JBR 25.
    // Gradle finds JDK 25 among the installed JDKs (including the JDK Gradle itself runs on) or downloads it (foojay).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 22
    options.encoding = "UTF-8"
    // "restricted" = calls to restricted FFM methods, which is the whole point of the mac package.
    options.compilerArgs.addAll(listOf("-Xlint:all,-restricted,-options,-processing,-serial"))
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        if (platformLocalPath != null) {
            // Local development: the installed IDE, no download.
            local(platformLocalPath.path)
        } else {
            // CI and fresh clones: downloaded (and cached) by Gradle.
            create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        }

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })
    }
}

// Signing: the CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD environment variables (CI, see release.yml).
// Local signing: when those two are not set, chain.crt + private_encrypted.pem from jetbrainsSignDir are used, with the
// password from PRIVATE_KEY_PASSWORD. Without either, signPlugin is skipped and nothing else is affected.
val certificateChainEnv = providers.environmentVariable("CERTIFICATE_CHAIN").filter { it.isNotBlank() }
val privateKeyEnv = providers.environmentVariable("PRIVATE_KEY").filter { it.isNotBlank() }
val localSigningFiles: Pair<File, File>? =
    if (certificateChainEnv.isPresent || privateKeyEnv.isPresent) {
        null
    } else {
        val signDir = file(localSetting("jetbrainsSignDir") ?: "${providers.systemProperty("user.home").get()}/.jetbrains-sign")
        (signDir.resolve("chain.crt") to signDir.resolve("private_encrypted.pem"))
            .takeIf { (chain, key) -> chain.isFile && key.isFile }
    }

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    // No Configurable of its own (the settings live in Advanced Settings): nothing to index.
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null } // open-ended
        }
    }

    signing {
        if (localSigningFiles != null) {
            certificateChainFile = localSigningFiles.first
            privateKeyFile = localSigningFiles.second
        } else {
            certificateChain = certificateChainEnv
            privateKey = privateKeyEnv
        }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        // Do not report the OS module (com.intellij.modules.os.mac) as missing: Android Studio's product-info.json does not
        // declare the OS aliases, and the dependency is optional anyway.
        freeArgs = listOf("-ignore-os-arch")

        ides {
            if (platformLocalPath != null) {
                local(platformLocalPath)
                platformCanaryPath?.let { local(it) }
            } else {
                // `pluginVerificationIdes` in gradle.properties (explained there).
                create(providers.gradleProperty("pluginVerificationIdes").map { ides ->
                    ides.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                })
            }
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

intellijPlatformTesting {
    runIde {
        if (platformCanaryPath != null) {
            // ./gradlew runIdeCanary : a second installed IDE, e.g. an Android Studio canary (build 262)
            register("runIdeCanary") {
                localPath = platformCanaryPath
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    signPlugin {
        val keyFile = privateKeyFile
        val keyPassword = password
        doFirst {
            if (keyFile.isPresent && !keyPassword.isPresent) {
                throw GradleException("Signing with ${keyFile.get().asFile} needs its password in the PRIVATE_KEY_PASSWORD environment variable.")
            }
        }
    }

    test {
        useJUnitPlatform()
        // The decoder uses java.lang.foreign; the IDE itself runs with the same flag.
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
        javaLauncher = project.javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

if (platformCanaryPath == null) {
    tasks.register("runIdeCanary") {
        group = "intellij platform"
        description = "Runs the plugin in a second installed IDE (set platformCanaryPath in local.properties to enable)."
        doLast {
            throw GradleException("platformCanaryPath is not set or does not exist; set it in local.properties (see README.md)")
        }
    }
}
