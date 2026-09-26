# HEIC Viewer

[![Build](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml/badge.svg)](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [简体中文](README.zh-CN.md)

<!-- Plugin description -->
Adds the HEIC/HEIF image format to the IDE. Files with the extensions `.heic`, `.heif`, `.hif` and `.heics` open in the
IDE's built-in image viewer and in the VCS image diff, just like PNG and JPEG. The images are decoded by the operating
system's own HEIF decoder:

- **macOS**: built in, nothing to install.
- **Windows 10 and 11**: the *HEIF Image Extension* and the *HEVC Video Extensions* from the Microsoft Store (some PCs
  come with both).
- **Linux**: libheif with its HEVC decoder (libde265) from the distribution's packages.

If a component is missing, a banner above the image says what to install; *Check Again* then shows the image without
restarting the IDE.

The plugin bundles no decoder and no native code, and sends no data. It installs, updates and uninstalls without
restarting the IDE.

[Source code and issue tracker](https://github.com/ZhuJHua/intellij-heic-viewer)
<!-- Plugin description end -->

## Screenshots

A HEIC file in the built-in image viewer:

![HEIC image in the built-in viewer](.github/readme/viewer.png)

Side-by-side image diff of a modified HEIC file:

![Git image diff of a HEIC file](.github/readme/diff.png)

## Requirements

- An IntelliJ-based IDE on IntelliJ Platform **241.14494 or newer**: IntelliJ IDEA / PyCharm / WebStorm / GoLand / …
  **2024.1** or newer, **Android Studio Koala** (2024.1.1) or newer.
- **macOS**, Apple silicon or Intel: nothing to install.
- **Windows 10 (1809 or newer) or 11**, x64 or arm64: the *HEIF Image Extension* and the *HEVC Video Extensions* from
  the Microsoft Store; see [Windows: HEIF and HEVC extensions](#windows-heif-and-hevc-extensions).
- **Linux**, x64 or arm64: libheif 1.x (`libheif.so.1`) with its HEVC decoder (libde265); see
  [Linux: libheif](#linux-libheif).

## Installation

- **JetBrains Marketplace**: <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for
  "HEIC Viewer" > <kbd>Install</kbd>.
- **Manually**: download the latest `heic-viewer-<version>.zip` from
  [GitHub Releases](https://github.com/ZhuJHua/intellij-heic-viewer/releases/latest) (or from JetBrains Marketplace),
  then <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>.

### Windows: HEIF and HEVC extensions

On Windows, HEIC images are decoded by the Windows Imaging Component with two packages from the Microsoft Store:

| Package | Store ID | Provides |
|---|---|---|
| *HEIF Image Extension* | [9PMMSR1CGPWG](https://apps.microsoft.com/detail/9PMMSR1CGPWG) | the HEIF decoder (free) |
| *HEVC Video Extensions* | [9NMZLZ57R3T7](https://apps.microsoft.com/detail/9NMZLZ57R3T7) | the HEVC codec of HEIC photos |
| *HEVC Video Extensions from Device Manufacturer* | [9N4WGH0Z6VHQ](https://apps.microsoft.com/detail/9N4WGH0Z6VHQ) | the same codec, preinstalled on some PCs |

If one is missing, the banner above a HEIC image names it, with *Open Microsoft Store* and *Check Again*; under *More*
are the Store's web page, *Learn More* (this section) and, for the HEIF Image Extension, a `winget` command to copy.
After installing, *Check Again*, or switching back to the IDE, shows the HEIC images. If the extension is still reported
as missing right after installing it, restart the IDE.

- `Get-AppxPackage *HEIFImageExtension*; Get-AppxPackage *HEVCVideoExtension*` in PowerShell lists what is installed.
- Windows "N" editions need the *Media Feature Pack* (Settings > Apps > Optional features) first.
- Editions without the Microsoft Store (Windows Server, LTSC) cannot get the HEVC codec from the Store.

### Linux: libheif

On Linux, HEIC images are decoded by the system's libheif (`libheif.so.1`) with its HEVC decoder, libde265. If either is
missing, the banner above a HEIC image shows the install command for the distribution, with *Copy Command*; after
installing, *Check Again*, or switching back to the IDE, shows the HEIC images.

| Distribution | Command |
|---|---|
| Ubuntu 23.10 and newer, Debian 13 and newer, and derivatives | `sudo apt install libheif1 libheif-plugin-libde265` |
| Ubuntu 20.04 / 22.04, Debian 11 / 12, and derivatives | `sudo apt install libheif1` |
| Fedora (the HEVC decoder comes from RPM Fusion Free) | `sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm && sudo dnf install libheif-freeworld` |
| RHEL, AlmaLinux, Rocky Linux, CentOS Stream (libheif from EPEL, the HEVC decoder from RPM Fusion Free) | `sudo dnf install --nogpgcheck https://dl.fedoraproject.org/pub/epel/epel-release-latest-$(rpm -E %rhel).noarch.rpm https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-$(rpm -E %rhel).noarch.rpm && sudo /usr/bin/crb enable && sudo dnf install libheif-freeworld` |
| openSUSE Tumbleweed, Slowroll, Leap (the HEVC decoder comes from Packman Essentials) | `sudo zypper addrepo -cfp 90 https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/ packman-essentials && sudo zypper --gpg-auto-import-keys refresh packman-essentials && sudo zypper install --from packman-essentials libheif1 libheif-HEIF` (`openSUSE_Slowroll`, `openSUSE_Leap_15.6`, ... for the others) |
| Arch Linux, Manjaro, EndeavourOS, ... | `sudo pacman -S --needed libheif libde265` |
| Alpine | `sudo apk add libheif` |

When libheif is installed but has no HEVC decoder, the banner asks for the decoder only, e.g.
`sudo apt install libheif-plugin-libde265` on Debian and Ubuntu, `sudo apk add libheif-libde265` on Alpine.

- libheif is loaded as `libheif.so.1` through the system's library search path. Where libheif is not on that path
  (e.g. NixOS, or a libheif you built yourself), start the IDE with its directory in `LD_LIBRARY_PATH`.
- **Flatpak** builds of an IDE only see the libraries of their Flatpak runtime. The freedesktop runtime 25.08 and newer
  includes libheif, and its HEVC decoder is the `org.freedesktop.Platform.codecs-extra` extension, which Flatpak
  installs with the runtime. If the extension is missing, the banner shows
  `flatpak install flathub org.freedesktop.Platform.codecs-extra//<branch>-extra`; run it in a terminal on the host,
  not in the IDE's terminal. Older runtimes cannot decode HEIC: use the IDE from the JetBrains Toolbox App, a tarball or
  a Snap.
- `idea.log` names the libheif that was found, or which libraries were tried, in the line that starts with
  `HEIC decoder:`.

## Limitations

- Without the system decoder (see [Requirements](#requirements)), HEIC files open as images but show "Image not
  loaded"; the banner above the image (and, for the diff, a notification once per session) says what to install. Diffs
  that are already open have to be opened again after installing.
- Only the **primary image** of a file is shown (no other images of collections, frames of `.heics` sequences, depth
  maps, …).
- Colors are converted to **sRGB**; **HDR gain maps are ignored** (the standard dynamic range image is shown).
- Images are decoded at full resolution and hold 4 bytes per pixel of the IDE's memory while they are shown, like PNG
  and JPEG. An image of more than about 2.1 gigapixels shows "Image not loaded"; on Linux, so does an image of more than
  about 268 megapixels.
- Truncated files show "Image not loaded". A file of full length whose compressed data is damaged may appear black.
- HEIC editor tabs that were open while the plugin was installed have to be reopened.
- Image dimensions in completion and documentation popups appear for HEIC files indexed before the plugin was installed
  only after the files change or the index is rebuilt.
- After the plugin is uninstalled, `.heic` and the other extensions may stay associated with the Image file type
  (*Settings | Editor | File Types*). To stop HEIC files from opening as images, disable the plugin.
- AVIF files are not handled.
- **Windows**: 8-bit monochrome HEIC images decode as black.
- **Linux**: libheif decodes in software, so large images take longer than on macOS. The orientation comes from the
  HEIF transformations (`irot`, `imir`); an EXIF orientation alone is ignored. libheif 1.6 (Ubuntu 20.04) cannot decode
  images with transparency or 10 bits per channel.

## Development

### Requirements

- Builds and tests on macOS, Windows and Linux. The decoder tests run where the system decoder is available; the rest
  runs everywhere. The tests of the Linux backend also run on macOS against Homebrew's libheif
  (`brew install libheif`), or against any libheif given as `HEIC_TEST_LIBHEIF=/path/to/libheif`.
- JDK 25 as the Gradle toolchain (the code is compiled with `--release 17`), plus JDK 21 and JDK 17 for `testJdk21` /
  `testJdk17`. Gradle picks up installed JDKs, including the one it runs on; otherwise the foojay resolver downloads
  them.
- Gradle 9.4.1 (wrapper included), IntelliJ Platform Gradle Plugin 2.19.0.

### Local IDE instead of a download (`local.properties`)

Without further settings the build downloads and caches the IDE named by `platformType` / `platformVersion` in
`gradle.properties`. To compile, run and verify against an installed IDE instead, create `local.properties` in the
project root (git-ignored):

```properties
# Installed IDE to compile against, run (runIde) and verify with
platformLocalPath=/Applications/Android Studio.app
# Optional second installed IDE: runIdeCanary and verifyPlugin
platformCanaryPath=/Applications/Android Studio Preview.app
# Optional: chain.crt + private_encrypted.pem for local signing (default ~/.jetbrains-sign)
#jetbrainsSignDir=/path/to/signing
```

The same names can be passed as Gradle properties (`-PplatformLocalPath=...`, or in `~/.gradle/gradle.properties`),
which take precedence. A blank value disables a setting, e.g. `./gradlew build -PplatformLocalPath=`.

To use an IDE's bundled JBR 25 as the toolchain, run Gradle on it
(`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`), or pass
`-Porg.gradle.java.installations.paths=/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

### Tasks

```bash
./gradlew buildPlugin      # build/distributions/heic-viewer-<version>.zip
./gradlew test             # JUnit 5 tests on JDK 25, including the light-IDE tests
./gradlew testJdk21        # the same tests on JDK 21 (runtime of IDEs 2024.2 - 2026.1.2), light-IDE tests excluded
./gradlew testJdk17        # the same tests on JDK 17 (IntelliJ 2024.1), tests tagged "platform" excluded
./gradlew check            # test, testJdk21 and testJdk17
./gradlew testJdk25        # `test` as a plain Test task, light-IDE tests excluded
./gradlew verifyPlugin     # Plugin Verifier: local IDEs, or pluginVerificationIdes from gradle.properties
./gradlew runIde           # sandboxed IDE with the plugin
./gradlew runIdeCanary     # sandboxed second IDE (needs platformCanaryPath)
./gradlew clean buildPlugin test testJdk21 testJdk17 verifyPlugin   # everything CI checks
```

The run configurations in `.run/` (Run Plugin, Run Tests, Run Verifications) wrap the same tasks.

`verifyPluginProjectConfiguration` notes that since-build 241 is lower than the platform compiled against and that
Java 17 is lower than the Java that platform requires; both are intended.

The platform jars of the IDE compiled against are Java 21 bytecode, so `testJdk17` runs with a minimal class path (the
plugin jar, JUnit and the IDE's `util-8.jar`, which contains JNA). Tests that need other IDE classes are tagged
`platform` and run only on JDK 21 and 25. The `*PlatformIntegrationTest` classes start a light IDE and run only in
`test`. On Intel Macs the test tasks run one at a time.

### Project structure

```
.github/                      GitHub Actions workflows (build, cross-platform, release), Dependabot, issue templates
.run/                         Shared IDE run configurations
gradle/libs.versions.toml     Version catalog (IntelliJ Platform Gradle Plugin, Changelog plugin, JUnit)
src/main/java/cn/yooss/heic/
  HeifSniffer                 Pure-Java ftyp sniffing (never touches native code)
  HeicImageReaderSpi          javax.imageio service provider (format names, suffixes, MIME types, canDecodeInput)
  HeicImageReader             Reader: input, size, image types, subsampling/source region
  HeicSupport                 Registration in IIORegistry
  HeicBundle                  Resource bundle
  HeicFileTypeMappingRepair   Keeps the HEIC extensions mapped to the Image file type
  HeicAppLifecycleListener, HeicDynamicPluginListener   Registration on start / dynamic load and unload
  InheritedContexts           Before unloading: threads started by plugin code release its class loader (Java 17-23)
  HeicReaderRegistrar         Registration in the command-line diff and merge (an editor provider that never accepts)
  backend/                    HeifBackend, HeifBackendStatus, HeifBackends (selection by OS), AbstractHeifBackend,
                              HeifImageInfo, HeifInput + IsoBoxes (input checks), PixelPipeline, PlaneConverter,
                              UnavailableHeifBackend, HeifRemedy + HeifRemedies (what the user can do about each
                              unavailable status); jna/JnaLibraries (JNA rules, library loading)
  mac/                        MacHeifBackend, HeicDecoder (ImageIO.framework), MacApi, jna/JnaMacApi
  win/                        WicHeifBackend, WicDecoder (WIC), WicProbe (availability), WinApi, jna/JnaWinApi,
                              Guids, Hresult, WindowsCodecs (Store product ids), SingleImageGrid + NclxTransfer (colors)
  linux/                      LibheifHeifBackend (probe), Libheif (the libheif C API through JNA), LibheifDecoder,
                              LinuxDistribution + LibheifRemedy (os-release, install commands)
  ui/                         Missing decoder: editor banner (HeicDecoderNotificationProvider, HeicFileOpenedListener),
                              notifications (DecoderPrompt), status and Check Again (DecoderStatus), refresh of the
                              views (HeicViews), remedy actions, HeicDiffExtension, HeicActivationListener
src/main/resources/
  META-INF/plugin.xml         Plugin id, name, vendor, dependencies and everything the plugin contributes
                              (description and change notes come from Gradle)
  META-INF/pluginIcon.svg, pluginIcon_dark.svg
  messages/HeicBundle.properties, HeicBundle_zh_CN.properties
src/test/java/                JUnit 5 tests; HeifBackendContractTest is the contract every backend must pass
src/test/resources/fixtures/  Synthetic test images (generated, no personal photos)
src/test/fixture-generators/  Sources and notes for generating the fixtures
CHANGELOG.md                  Keep a Changelog; the change notes of each release are generated from it
```

### Implementing a decoder backend

- Extend `backend.AbstractHeifBackend`, like `mac.MacHeifBackend`, `win.WicHeifBackend` and
  `linux.LibheifHeifBackend`; `HeifBackends` selects the backend by OS.
- `probe()`: load the system libraries through `backend.jna.JnaLibraries` and return
  `HeifBackendStatus.available(...)` or `unavailable(Reason, detail)` with `withInstallUrl` or `withInstallCommand`.
- What the user sees for a reason is its remedy in `backend.HeifRemedies`: the texts `remedy.title.<REASON>` and
  `backend.status.<REASON>` in both message bundles, and the actions. `HeifRemediesTest` checks every remedy.
- `doReadInfo` / `doDecode`: the input checks, the availability check and the wrapping of native failures are done by
  the base class. Produce the images with `PixelPipeline` (8-bit sRGB, straight alpha, orientation applied).
- Follow the JNA rules in `JnaLibraries`; `BytecodeLevelTest` and `PluginClassLoaderLeakTest` check them.
- `HeifBackendContractTest` must pass on the OS; in `.github/workflows/cross-platform.yml`, set `expect` of each job to
  the status the job must report (`available`, or e.g. `LINUX_LIBHEIF_MISSING`).
- `-Dheic.viewer.debug.backendStatus=<REASON>[|<url>[|<command>]]` shows the banner and notifications of any status on
  any OS (with `-Dheic.viewer.debug.backendStatus.recover=true`, *Check Again* then finds the real decoder).

### Plugin description and change notes

- The description shown on JetBrains Marketplace and in the IDE's plugin manager is the Markdown between
  `<!-- Plugin description -->` and `<!-- Plugin description end -->` at the top of this file; the build converts it to
  HTML and writes it into `plugin.xml`. It must stay in English.
- The change notes come from `CHANGELOG.md` ([Keep a Changelog](https://keepachangelog.com) format): the section of
  the current version, or `## [Unreleased]` if there is none. New entries go under `## [Unreleased]`.

### Continuous integration and releases

- `.github/workflows/build.yml` runs on every push to `main` and on pull requests: build and tests (`check`), Plugin
  Verifier against `pluginVerificationIdes`, and a draft GitHub release (from the `[Unreleased]` section of
  `CHANGELOG.md`) for pushes to `main`.
- `.github/workflows/cross-platform.yml` runs on pushes to `dev/**` branches, on pull requests to `main` and manually:
  build and the tests on JDK 25, 21 and 17 on macOS, Windows and Linux (with and without the system decoder), the
  light-IDE tests, Plugin Verifier, and the *Linux install commands* job, which runs the install command the plugin
  suggests in containers of several distributions and checks that libheif is then found and decodes. Each job sets
  `HEIC_EXPECT_BACKEND` to the decoder status `HeifBackendContractTest` must see.
- Publishing the draft release triggers `.github/workflows/release.yml`: it moves the release notes into a version
  section of `CHANGELOG.md`, signs and publishes the plugin to JetBrains Marketplace, attaches the zip to the release
  and opens a pull request with the updated changelog. Required repository secrets: `PUBLISH_TOKEN`,
  `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`
  (see [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)). The workflow needs
  *Settings | Actions | General | Workflow permissions | Allow GitHub Actions to create and approve pull requests* to
  open the pull request; without it, open it by hand from the pushed `changelog-update-<version>` branch.
- Bump `pluginVersion` in `gradle.properties` for every release.
- Local signing: `PRIVATE_KEY_PASSWORD=... ./gradlew signPlugin verifyPluginSignature` uses the
  `CERTIFICATE_CHAIN` / `PRIVATE_KEY` environment variables, or else `chain.crt` + `private_encrypted.pem` from
  `jetbrainsSignDir` (default `~/.jetbrains-sign`). Without any of them `signPlugin` is skipped.

## License

[MIT](LICENSE) © 2026 ZhuJHua
