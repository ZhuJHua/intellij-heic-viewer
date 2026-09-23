# HEIC Viewer

[![Build](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml/badge.svg)](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [简体中文](README.zh-CN.md)

<!-- Plugin description -->
Opens HEIC/HEIF images in the IDE's built-in image viewer and VCS image diff (**macOS only**).

Files with the extensions `.heic`, `.heif`, `.hif` and `.heics` become regular images, just like PNG or JPEG:

- **Built-in image viewer**: zoom, grid, chessboard background and the size/format info label.
- **Image diff**: changed HEIC files are compared side by side in the version control (VCS) diff.
- **Thumbnails as file icons**: local HEIC files (up to 64 MB) show a small preview instead of the generic image icon
  in the Project view, editor tabs and file lists. Thumbnails are decoded in the background and cached.
- **Upright images**: EXIF orientation and the HEIF `irot`/`imir` transformations are applied.
- Transparency, 10-bit images, grid (tiled) images, image collections and `.heics` sequences (the primary image is
  shown).
- Very large images are downscaled while decoding to keep memory use bounded (64 megapixels by default).
- Installs, updates and uninstalls without restarting the IDE.

Images are decoded by the HEIF decoder that is part of macOS, called through the Java FFM API; the plugin contains no
native code and does not send any data. The pixel limit and the thumbnails can be changed in
*Settings | Advanced Settings | HEIC Viewer*.

Limitations: only the primary image of a file is shown, colors are converted to sRGB, and HDR gain maps are ignored.
On Windows and Linux the plugin currently does nothing.

[Source code and issue tracker](https://github.com/ZhuJHua/intellij-heic-viewer)
<!-- Plugin description end -->

## Screenshots

A HEIC file in the built-in image viewer, with HEIC thumbnails in the Project view:

![HEIC image in the built-in viewer](.github/readme/viewer.png)

Side-by-side image diff of a modified HEIC file:

![Git image diff of a HEIC file](.github/readme/diff.png)

## Requirements

- **macOS** (tested on macOS 26 on Apple silicon; the code is architecture-independent).
- An IntelliJ-based IDE on IntelliJ Platform **261.26222 or newer** that runs on Java 22+ (the FFM API):
  Android Studio 2026.1.3 (Quail 3) or newer, IntelliJ IDEA / PyCharm / WebStorm / … 2026.1.4 or newer, and every
  2026.2+ release. Android Studio 2026.1.1 and 2026.1.2 are not supported because they run on JBR 21.

## Installation

- **JetBrains Marketplace**: <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for
  "HEIC Viewer" > <kbd>Install</kbd>.
- **Manually**: download the latest `heic-viewer-<version>.zip` from
  [GitHub Releases](https://github.com/ZhuJHua/intellij-heic-viewer/releases/latest) (or from JetBrains Marketplace),
  then <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>.
  No restart is needed.

## Settings

*Settings | Advanced Settings | HEIC Viewer*:

| Setting | ID | Default |
|---|---|---|
| Maximum decoded image size (megapixels, 1–512). Larger images are downscaled while decoding; the longer side is also limited to 16384 pixels. The image viewer always asks for full resolution and the diff decodes two images at once, so this bounds memory use. | `heic.viewer.max.megapixels` | 64 |
| Show thumbnails as HEIC file icons. Applies as soon as the settings dialog is closed. | `heic.viewer.project.view.thumbnails` | on |

## Limitations and known issues

- **macOS only** for now: decoding relies on the system's ImageIO framework. On other systems the plugin can be
  installed but does nothing (the `.heic` extension is not claimed there).
- Colors are converted to **sRGB**; Display P3 colors outside sRGB are clipped.
- **HDR gain maps are ignored**: the standard dynamic range base image is shown.
- Only the **primary image** is shown (no other images of collections, frames of `.heics` sequences, depth maps, …).
- Images above the pixel budget are downscaled: the editor's info label shows the decoded size, while the
  documentation popup and completion show the original size.
- Truncated files (cut short, e.g. a partial copy or download) show "Image not loaded" (the reason is logged) instead
  of a black image. A file of full length whose compressed image data is damaged (bit flips, or a zero-filled tail
  left by an interrupted pre-allocated download) may still appear black, because ImageIO.framework reports no error
  for it.
- **IJPL-39443**: if the IDE saved its settings while the plugin was unloaded, it writes
  `<removed_mapping ext="heic" type="Image"/>` to `filetypes.xml`. The plugin re-associates such unmapped extensions
  with the Image file type on start (extensions explicitly mapped to another type are left alone). This leaves an
  explicit mapping in `filetypes.xml` after uninstalling, which can be removed in *Settings | Editor | File Types*.
  To stop HEIC files from opening as images, disable the plugin rather than removing the extension.
- After installing without restart, HEIC editor tabs that were already open must be reopened.
- Image dimensions in completion and documentation popups appear for HEIC files indexed before the plugin was
  installed only after the files change or the index is rebuilt.
- AVIF is out of scope (AVIF headers are explicitly rejected).
- Thumbnails appear wherever file icons are shown (a `FileIconProvider` cannot tell where it is called from).
  Non-local files (inside archives, remote, historical revisions in the diff) and files over 64 MB keep the generic
  icon. The first time a file is shown, the generic icon appears briefly; folders with many HEIC files fill in two at a
  time. Up to 500 icons are cached.
- Not verified yet: Intel Macs, macOS versions before 26, HEIC files stored with Git LFS.

## How it works

1. **File type**: `META-INF/heic-viewer-macos.xml` declares `<fileType name="Image" extensions="heic;heif;hif;heics"/>`
   without an implementation class, which merges the extensions into the platform's existing "Image" file type (the
   same technique the bundled WebP support uses). The image editor and the binary diff viewer then handle HEIC files.
2. **ImageIO reader**: the IDE's image editor, diff (`IfsUtil`) and image info index (`ImageInfoReader`) choose a
   `javax.imageio` reader by content. `HeicImageReaderSpi.canDecodeInput` runs only the pure-Java `HeifSniffer`
   (reads at most 512 bytes of the `ftyp` box), never loads native code and never throws, so other formats are never
   affected. AVIF and MP4/MOV brands are rejected. The format name is `heic`, so the info label says `HEIC`.
3. **Decoding** (`mac` package): `MacImageIO` binds CoreFoundation, ImageIO, CoreGraphics and libobjc through the Java
   FFM API (initialized on the first decode). `HeicDecoder` creates a `CGImageSource` (no caching), reads the primary
   image's properties, creates a transformed (orientation-applied) image, draws it in bands of about one megapixel into
   an explicit 8-bit sRGB bitmap context and copies the pixels into a `BufferedImage`. Every call has its own
   autorelease pool and confined `Arena`, and all CF objects are released in `finally` blocks. Before any native call,
   the data must pass `HeifSniffer` (ImageIO.framework picks the codec by content, so other formats must never reach
   it), and `IsoBoxes` checks in pure Java that the top-level boxes and the `iloc` data are complete, because
   ImageIO.framework "successfully" decodes truncated files as black images. The image source must then report a
   HEIF-family type (`public.heic`, `public.heif`, …).
4. **Registration**: `AppLifecycleListener.appFrameCreated` (normal start, before projects and editor tabs are
   restored), `DynamicPluginListener.pluginLoaded` / `beforePluginUnload` (installation, update and removal without
   restart), and `HeicReaderRegistrar`, a `fileEditorProvider` for the Image file type whose `accept` registers the
   reader and always returns `false`: the command-line diff and merge (e.g. `studio diff a.heic b.heic` while the IDE is
   not running) never create an IDE frame, but ask the editor providers before they create their image viewers. It
   never creates an editor, so loading or unloading the plugin leaves open editors and diff windows alone. After
   `beforePluginUnload` nothing registers the reader again. Stale copies left by an old plugin class loader are
   removed, and `IIORegistry.setOrdering` makes this reader win over other plugins' HEIF readers. Deliberately not
   `ApplicationLoadListener` (internal API, not dynamic), not the 262-only `imageReaderWriterSpi` extension point
   (unknown on 261, which would block dynamic unloading), and no `META-INF/services` entry.
   After registering, `HeicSupport` checks that `ImageIO` itself finds the reader. `IIORegistry.getDefaultInstance()`
   is not thread-safe, and if two threads call it for the first time at once while the IDE starts, `ImageIO` can keep a
   different registry for the whole session (the most likely cause of a one-off start on an Android Studio 2026.2
   canary in which no HEIC image loaded). In that case a second instance is
   added to `ImageIO`'s registry through `ImageIO.scanForPlugins()`, with a context class loader that names only this
   reader. It is removed again on unload, and the split is logged as a warning in `idea.log`.
5. **Thumbnails** (`thumbnail` package): `HeicThumbnailIconProvider` (`fileIconProvider`, `order="first"`) never
   decodes in `getIcon`: it looks up an LRU cache (500 entries, keyed by URL, VFS timestamp, length, icon size and the
   maximum screen scale) and otherwise queues a decode on the plugin's own bounded executor (two threads) and returns
   `null`, so the platform shows the default Image icon. The background task reads the file directly from disk (only
   if its header is a HEIC/HEIF `ftyp` box: a file merely named `.heic` never reaches ImageIO.framework), calls
   `HeicDecoder.decodeThumbnail` (which may use the file's embedded thumbnail) and renders a HiDPI-aware square icon
   with Java2D (progressive bilinear downscaling, centered, a faint one-pixel outline). When the icon is ready,
   `VirtualFileAppearanceListener.fireVirtualFileAppearanceChanged` refreshes the Project view, tabs and navigation
   bar. Only platform and JDK objects are handed to the platform, and `beforePluginUnload` shuts the executor down,
   waits up to one second for running decodes and clears the caches, so the plugin class loader can be unloaded.
6. **macOS only**: every extension lives in `META-INF/heic-viewer-macos.xml`, loaded through
   `<depends optional="true" config-file="heic-viewer-macos.xml">com.intellij.modules.os.mac</depends>`.

## Development

### Requirements

- macOS for the decoder tests (they call the system's ImageIO framework); everything else builds anywhere.
- JDK 25 as the Gradle toolchain (the code is compiled with `--release 22`). Gradle picks up any installed JDK 25,
  including the one it runs on; otherwise the foojay resolver downloads one (see below to reuse an IDE's JBR 25).
- Gradle 9.4.1 (wrapper included), IntelliJ Platform Gradle Plugin 2.19.0.

### Local IDE instead of a download (`local.properties`)

Without further settings the build downloads and caches the IDE named by `platformType` / `platformVersion` in
`gradle.properties` (Android Studio 2026.1.4.8, about 1.5 GB). To compile, run and verify against an installed IDE
instead, create `local.properties` in the project root (git-ignored):

```properties
# Installed IDE to compile against, run (runIde) and verify with
platformLocalPath=/Applications/Android Studio.app
# Optional second installed IDE: runIdeCanary and verifyPlugin
platformCanaryPath=/Applications/Android Studio Preview.app
# Optional: chain.crt + private_encrypted.pem for local signing (default ~/.jetbrains-sign)
#jetbrainsSignDir=/path/to/signing
```

The same names can be passed as Gradle properties (`-PplatformLocalPath=...`, or in `~/.gradle/gradle.properties`),
which take precedence. A blank value disables a setting, e.g. `./gradlew build -PplatformLocalPath=` builds exactly
like CI.

**JDK 25 from the IDE**: Gradle toolchains cannot be configured from `local.properties`. To reuse an IDE's bundled
JBR 25 instead of a download, either run Gradle on it
(`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`, or select the IDE's bundled JBR as
the Gradle JVM in the IDE), or pass
`-Porg.gradle.java.installations.paths=/Applications/Android Studio.app/Contents/jbr/Contents/Home`
(also possible in `~/.gradle/gradle.properties`).

### Tasks

```bash
./gradlew buildPlugin      # build/distributions/heic-viewer-<version>.zip
./gradlew test             # JUnit 5 tests (decoder tests need macOS)
./gradlew verifyPlugin     # Plugin Verifier: local IDEs, or pluginVerificationIdes from gradle.properties
./gradlew runIde           # sandboxed IDE with the plugin
./gradlew runIdeCanary     # sandboxed second IDE (needs platformCanaryPath)
./gradlew clean buildPlugin test verifyPlugin   # everything CI checks
```

The run configurations in `.run/` (Run Plugin, Run Tests, Run Verifications) wrap the same tasks.

Expected warnings: `verifyPluginProjectConfiguration` notes that Java 22 bytecode is newer than the Java 21 that
since-build 261 requires; that is intended (FFM is final since Java 22 and every supported IDE runs on JBR 25).

### Project structure

```
.github/                      GitHub Actions workflows (build, release), Dependabot, issue templates
.run/                         Shared IDE run configurations
gradle/libs.versions.toml     Version catalog (IntelliJ Platform Gradle Plugin, Changelog plugin, JUnit)
src/main/java/cn/yooss/heic/
  HeifSniffer                 Pure-Java ftyp sniffing (never touches native code)
  HeicImageReaderSpi          javax.imageio service provider (format names, suffixes, MIME types, canDecodeInput)
  HeicImageReader             Reader: input, size, image types, subsampling/source region/pixel budget
  HeicBackend                 Decoder interface between the reader and the native layer (replaceable in tests;
                              the place for Windows/Linux decoders)
  DecodeLimits                Pixel budget
  HeicSupport                 Registration in IIORegistry (platform check, ordering, stale copies, split registry)
  HeicSettings, HeicBundle    Advanced Settings access, resource bundle
  HeicFileTypeMappingRepair   IJPL-39443 workaround
  HeicAppLifecycleListener, HeicDynamicPluginListener   Registration on start / dynamic load and unload
  HeicReaderRegistrar         Registration in the command-line diff and merge (an editor provider that never accepts)
  mac/                        MacImageIO (FFM bindings), HeicDecoder, IsoBoxes (truncation check)
  thumbnail/                  Thumbnail icons: provider, loader, cache, renderer, listeners
src/main/resources/
  META-INF/plugin.xml         Plugin id, name, vendor, dependencies (description and change notes come from Gradle)
  META-INF/heic-viewer-macos.xml   Everything the plugin contributes (loaded only on macOS)
  META-INF/pluginIcon.svg, pluginIcon_dark.svg
  messages/HeicBundle.properties, HeicBundle_zh_CN.properties
src/test/java/                JUnit 5 tests
src/test/resources/fixtures/  Synthetic test images (generated, no personal photos)
src/test/fixture-generators/  Sources and notes for generating the fixtures
CHANGELOG.md                  Keep a Changelog; the change notes of each release are generated from it
```

### Plugin description and change notes

- The description shown on JetBrains Marketplace and in the IDE's plugin manager is the Markdown between
  `<!-- Plugin description -->` and `<!-- Plugin description end -->` at the top of this file; the build converts it to
  HTML and writes it into `plugin.xml`. It must stay in English.
- The change notes come from `CHANGELOG.md` ([Keep a Changelog](https://keepachangelog.com) format): the section of
  the current version, or `## [Unreleased]` if there is none. New entries go under `## [Unreleased]`.

### Continuous integration and releases

- `.github/workflows/build.yml` runs on every push to `main` and on pull requests: build and tests on `macos-latest`
  (the decoder tests need macOS), Plugin Verifier on `ubuntu-latest` against `pluginVerificationIdes`, and a draft
  GitHub release (from the `[Unreleased]` section of `CHANGELOG.md`) for pushes to `main`.
- Publishing the draft release triggers `.github/workflows/release.yml`: it moves the release notes into a version
  section of `CHANGELOG.md`, signs and publishes the plugin to JetBrains Marketplace, attaches the zip to the release
  and opens a pull request with the updated changelog. Required repository secrets: `PUBLISH_TOKEN`,
  `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`
  (see [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)).
  Also enable *Settings | Actions | General | Workflow permissions | Allow GitHub Actions to create and approve pull
  requests* (off by default for new personal-account repositories). Otherwise the last step cannot open the changelog
  pull request (the workflow logs a warning) and it has to be opened by hand from the pushed
  `changelog-update-<version>` branch. Merge it before the next release, or the next draft repeats the released entries.
- Bump `pluginVersion` in `gradle.properties` for every release.
- First release (0.1.0): the first upload to JetBrains Marketplace has to be done by hand. Build and sign locally
  (`PRIVATE_KEY_PASSWORD=... ./gradlew signPlugin`), upload `build/distributions/heic-viewer-0.1.0-signed.zip` on
  JetBrains Marketplace (Upload plugin), then publish the 0.1.0 draft release. The Release workflow skips the
  Marketplace upload for 0.1.0, but it still attaches the zip and opens the changelog pull request. From then on, bump
  `pluginVersion` and publish the draft.
- Local signing: `PRIVATE_KEY_PASSWORD=... ./gradlew signPlugin verifyPluginSignature` uses the
  `CERTIFICATE_CHAIN` / `PRIVATE_KEY` environment variables, or else `chain.crt` + `private_encrypted.pem` from
  `jetbrainsSignDir` (default `~/.jetbrains-sign`). Without any of them `signPlugin` is skipped.

## Roadmap

- 0.2: Windows and Linux support through the systems' own decoders, behind the existing `HeicBackend` interface.

## License

[MIT](LICENSE) © 2026 ZhuJHua
