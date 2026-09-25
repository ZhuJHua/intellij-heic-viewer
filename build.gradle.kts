import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
    // A JDK 25 toolchain compiles everything with --release 17 (below): the oldest supported IDEs (2024.1, since-build
    // 241.14494) run on JBR 17. Gradle finds JDK 25 among the installed JDKs (including the JDK Gradle itself runs on)
    // or downloads it (foojay).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Main and test classes: Java 17 bytecode and API (the same test classes run on JDK 17, 21 and 25, see below).
    // BytecodeLevelTest checks the plugin jar (class versions, no java.lang.foreign, no record ObjectMethods).
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-options,-processing,-serial"))
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
    // HeicPlatformIntegrationTest: a light IDE (BasePlatformTestCase, JUnit 4 style) in the `test` task only.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)

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

        testFramework(TestFrameworkType.Platform)
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
        // Verify the plugin independently of the OS/architecture of the IDE build that is checked (the plugin supports
        // macOS, Windows and Linux; the verifier would otherwise skip IDE modules of other systems).
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

    // verifyPluginSignature reads signPlugin's output (build/distributions/*-signed.zip) but the IntelliJ Platform Gradle
    // Plugin does not declare the dependency, so Gradle 9 rejects `./gradlew signPlugin verifyPluginSignature`.
    verifyPluginSignature {
        dependsOn(signPlugin)
    }

    test {
        // JDK 25: the runtime of IDEs 2026.1.3+ (Android Studio Quail 3+).
        javaLauncher = project.javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        systemProperty("heic.test.javaVersion", 25)
    }
}

// The IDE's JNA (com.sun.jna in lib/util-8.jar) is on the test class path, but its native part ships separately in
// <IDE>/lib/jna/<arch>; point JNA there exactly like the IDE launcher does (product-info.json: -Djna.boot.library.path,
// -Djna.nosys=true, -Djna.noclasspath=true). Directory names as in the IDE distributions: aarch64 or amd64, on macOS,
// Windows and Linux alike. Evaluated only when a test task runs (resolving platformPath needs the IDE).
val platformDir: Provider<File> = providers.provider { intellijPlatform.platformPath.toFile() }
val jnaNativeDir: Provider<String> = platformDir.map { platform ->
    val jna = platform.resolve("lib/jna")
    val arch = System.getProperty("os.arch").lowercase()
    val preferred = if (arch == "aarch64" || arch == "arm64") "aarch64" else "amd64"
    // Not another architecture's directory: e.g. Android Studio for Linux and Windows ships x64 only.
    jna.resolve(preferred).takeIf { it.isDirectory }?.absolutePath ?: ""
}

/** JVM arguments that make JNA load its native library from the IDE, if the IDE has one for this architecture. */
class JnaNativeArgs(@get:Input val nativeDir: Provider<String>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = nativeDir.get().takeIf { it.isNotEmpty() }
        ?.let { listOf("-Djna.boot.library.path=$it", "-Djna.nosys=true", "-Djna.noclasspath=true") }
        ?: emptyList()
}

/** Tells BytecodeLevelTest which jar to scan: the plugin jar that goes into the distribution. */
class PluginJarArg(@get:InputFile @get:PathSensitive(PathSensitivity.NONE) val jar: Provider<RegularFile>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dheic.test.pluginJar=${jar.get().asFile.absolutePath}")
}

val pluginJar: Provider<RegularFile> = tasks.named<AbstractArchiveTask>("composedJar").flatMap { it.archiveFile }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgumentProviders.add(PluginJarArg(pluginJar))
    // What the IDE launcher passes as well: java.lang for JnaLibraries (clears the inherited access control context of
    // a JNA Cleaner thread started by plugin code, see PluginClassLoaderLeakTest).
    jvmArgs("-Djava.awt.headless=true", "--add-opens=java.base/java.lang=ALL-UNNAMED")
    jvmArgumentProviders.add(JnaNativeArgs(jnaNativeDir))
    // Lets HeifBackendContractTest check what CI expects of the system decoder (e.g. "available" on macOS).
    providers.environmentVariable("HEIC_EXPECT_BACKEND").orNull?.let { systemProperty("heic.test.expectBackend", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// The same tests on the runtimes of the supported IDEs; `check` runs test, testJdk21 and testJdk17. These are plain Test
// tasks: unlike `test`, which the IntelliJ Platform Gradle Plugin prepares for IDE tests (and which therefore refuses to
// run on a CPU architecture the IDE compiled against has no build for, e.g. Android Studio on Linux/Windows arm64), they
// only reuse its class path. testJdk25 is `test` as such a plain task (CI runs testJdk25, testJdk21 and testJdk17).
fun registerTestOn(taskName: String, javaVersion: Int) = tasks.register<Test>(taskName) {
    group = "verification"
    description = "Runs the unit tests on JDK $javaVersion."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files(tasks.test.map { it.classpath })
    javaLauncher = project.javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
    systemProperty("heic.test.javaVersion", javaVersion)
    // Needs the IDE test environment that only `test` sets up.
    filter { excludeTestsMatching("cn.yooss.heic.HeicPlatformIntegrationTest") }
    shouldRunAfter(tasks.test)
}
// JBR 25: IDEs 2026.1.3+, Android Studio Quail 3+.
registerTestOn("testJdk25", 25)
// JBR 21: IDEs 2024.2 - 2025.3 and 2026.1 - 2026.1.2, Android Studio Ladybug - Quail 2.
val testJdk21 = registerTestOn("testJdk21", 21)
// JBR 17: IDEs 2024.1.x, Android Studio Koala.
val testJdk17 = registerTestOn("testJdk17", 17)
testJdk17.configure {
    // The platform jars of the IDE compiled against (2026.1) are Java 21 bytecode and cannot be loaded on JDK 17, except
    // util-8.jar (Java 8 bytecode: JNA, Logger, ...). The JDK 17 run gets a minimal class path: the plugin jar, JUnit
    // and util-8.jar. Tests that need other platform classes are tagged "platform" and run on JDK 21 and 25.
    classpath = sourceSets.test.get().output + files(pluginJar) +
        configurations.testRuntimeClasspath.get().filter { it.name.matches(Regex("(junit-|opentest4j|apiguardian).*")) } +
        files(platformDir.map { it.resolve("lib/util-8.jar") })
    useJUnitPlatform { excludeTags("platform") }
    // Test classes whose bytecode cannot even be verified without those platform classes (JUnit would fail discovery).
    filter { excludeTestsMatching("cn.yooss.heic.HeicReaderRegistrarTest") }
}
tasks.check { dependsOn(testJdk21, testJdk17) }

if (platformCanaryPath == null) {
    tasks.register("runIdeCanary") {
        group = "intellij platform"
        description = "Runs the plugin in a second installed IDE (set platformCanaryPath in local.properties to enable)."
        doLast {
            throw GradleException("platformCanaryPath is not set or does not exist; set it in local.properties (see README.md)")
        }
    }
}
