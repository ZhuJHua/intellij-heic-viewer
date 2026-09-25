<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# HEIC Viewer Changelog

## [Unreleased]

### Added

- Windows and Linux: HEIC/HEIF images are decoded by the operating system's own decoder (Windows: WIC with the *HEIF Image Extensions* and *HEVC Video Extensions* from the Microsoft Store; Linux: libheif with its libde265 HEVC plugin). Nothing is bundled.
- A notification tells what to install when the system decoder is missing, with the install page or command, *Check Again* (HEIC files then load without a restart) and *Don't Show Again*.
- Linux: the system's libheif 1.x (`libheif.so.1`, 1.12 or newer for images with transparency and 10-bit images) with its HEVC decoder is used; the notification shows the install command for Debian, Ubuntu and derivatives, Fedora (RPM Fusion), RHEL-compatible distributions (EPEL and RPM Fusion), openSUSE (Packman), Arch Linux, Alpine and NixOS. Embedded thumbnails are used for file icons, and ICC color profiles are converted to sRGB.
- Advanced Setting *libheif library* (Linux): the location of a libheif outside the system's library path.

### Changed

- Supports IntelliJ-based IDEs 2024.1 and newer (Android Studio Koala 2024.1.1 and newer; previously 2026.1.4 / Android Studio Quail 3): the macOS decoder is called through the JNA library that comes with the IDE instead of the Java FFM API, and the plugin is compiled for Java 17.
- The plugin loads on every operating system; `.heic` files are claimed as images everywhere, and the availability of the system decoder is checked at runtime.

## [0.1.0]

### Added

- HEIC/HEIF files (`.heic`, `.heif`, `.hif`, `.heics`) open in the built-in image viewer and in the side-by-side VCS image diff (macOS only), also in the diff and merge windows started from the command line (`studio diff`, `idea merge`, git difftool/mergetool …) while the IDE is not running.
- Decoding through the HEIF decoder built into macOS via the Java FFM API: EXIF and HEIF (`irot`/`imir`) orientation, transparency, 10-bit and grid images; the primary image of collections and `.heics` sequences.
- Thumbnails of local HEIC files as their icons in the Project view, editor tabs and file lists, decoded in the background and cached.
- Advanced Settings (*Settings | Advanced Settings | HEIC Viewer*): maximum decoded image size (default 64 megapixels) and thumbnail icons on/off.
- Truncated files show "Image not loaded" instead of a black image.
- Installs, updates and uninstalls without an IDE restart.
- Repairs the `.heic` file type mapping if the IDE dropped it while the plugin was unloaded (IJPL-39443).
- Robust reader registration: HEIC images also load when a startup race in `javax.imageio` leaves the IDE with two image reader registries; the condition is logged.
