<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# HEIC Viewer Changelog

## [Unreleased]

### Added

- Windows 10 (1809 or newer) and 11, x64 and arm64: HEIC/HEIF images are decoded by the Windows Imaging Component with the *HEIF Image Extension* (free) and the *HEVC Video Extensions* (paid; preinstalled on some PCs) from the Microsoft Store, with transparency, orientation and ICC color profiles (e.g. Display P3). Two color conversion errors of Microsoft's decoder are worked around in memory: single 8-bit images (not grids) no longer come out with the BT.709 matrix whatever the file signals (BT.601 red was (255, 25, 0)), and grids and 10-bit images with a BT.709 or unspecified transfer curve no longer come out with brightened shadows.
- Linux, x64 and arm64: HEIC/HEIF images are decoded by the system's libheif 1.x (`libheif.so.1`; 1.12 or newer for images with transparency and 10-bit images) with its HEVC decoder (libde265). Embedded thumbnails are used for file icons, and ICC color profiles are converted to sRGB.
- When the system decoder is missing, a banner above the HEIC image (and, for the diff and the thumbnails, a notification once per session) says what to install: on Windows the Microsoft Store page of the missing extension (and a winget command for the HEIF Image Extension), on Linux the install command for the detected distribution (Debian, Ubuntu and derivatives, Fedora and RHEL-compatible distributions with RPM Fusion, openSUSE with Packman, Arch Linux, Alpine, NixOS; for a Flatpak IDE on the freedesktop runtime 25.08 or newer, the runtime's `codecs-extra` extension). *Check Again*, or coming back to the IDE after installing, shows the open HEIC images without a restart (on Windows, if *Check Again* still reports the extension as missing, restart the IDE).
- Advanced Setting *libheif library* (Linux only): the location of a libheif outside the system's library path.

### Changed

- Supports IntelliJ-based IDEs 2024.1 and newer (Android Studio Koala 2024.1.1 and newer; previously 2026.1.4 / Android Studio Quail 3): the macOS decoder is called through the JNA library that comes with the IDE instead of the Java FFM API, and the plugin is compiled for Java 17.
- The plugin loads on every operating system; `.heic` files are claimed as images everywhere, and the availability of the system decoder is checked at runtime, in the background. The plugin still bundles no decoder and no native code.

### Fixed

- macOS: a malformed HEIC whose declared size does not match its coded image could keep the image viewer busy for about a minute (ImageIO decoded the whole image again for every band of about one megapixel); an image is now drawn in at most eight bands.
- macOS: images with transparency that are decoded smaller (the thumbnail icons) had darkened edges on some Macs (ImageIO's thumbnail scaler did not weight the colors by alpha); they are now downscaled alpha-weighted by the plugin, like on Windows and Linux.

## [0.1.0] - 2026-09-25

### Added

- HEIC/HEIF files (`.heic`, `.heif`, `.hif`, `.heics`) open in the built-in image viewer and in the side-by-side VCS image diff (macOS only), also in the diff and merge windows started from the command line (`studio diff`, `idea merge`, git difftool/mergetool …) while the IDE is not running.
- Decoding through the HEIF decoder built into macOS via the Java FFM API: EXIF and HEIF (`irot`/`imir`) orientation, transparency, 10-bit and grid images; the primary image of collections and `.heics` sequences.
- Thumbnails of local HEIC files as their icons in the Project view, editor tabs and file lists, decoded in the background and cached.
- Advanced Settings (*Settings | Advanced Settings | HEIC Viewer*): maximum decoded image size (default 64 megapixels) and thumbnail icons on/off.
- Truncated files show "Image not loaded" instead of a black image.
- Installs, updates and uninstalls without an IDE restart.
- Repairs the `.heic` file type mapping if the IDE dropped it while the plugin was unloaded (IJPL-39443).
- Robust reader registration: HEIC images also load when a startup race in `javax.imageio` leaves the IDE with two image reader registries; the condition is logged.

[Unreleased]: https://github.com/ZhuJHua/intellij-heic-viewer/compare/0.1.0...HEAD
[0.1.0]: https://github.com/ZhuJHua/intellij-heic-viewer/commits/0.1.0
