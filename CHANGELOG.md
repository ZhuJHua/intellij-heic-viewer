<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# HEIC Viewer Changelog

## [Unreleased]

### Added

- Windows 10 (1809 or newer) and 11, x64 and arm64: HEIC/HEIF images are decoded by the Windows Imaging Component with the *HEIF Image Extension* and the *HEVC Video Extensions* from the Microsoft Store, with transparency, orientation and ICC color profiles.
- Linux, x64 and arm64: HEIC/HEIF images are decoded by the system's libheif 1.x (`libheif.so.1`) with its HEVC decoder (libde265), with transparency, orientation and ICC color profiles.
- When the system decoder is missing, a banner above the HEIC image (and, for the diff, a notification once per session) says what to install: on Windows the Microsoft Store page of the missing extension, on Linux the install command for the detected distribution. *Check Again*, or coming back to the IDE after installing, shows the open HEIC images without a restart.

### Changed

- Supports IntelliJ-based IDEs 2024.1 and newer (Android Studio Koala 2024.1.1 and newer).
- HEIC images are decoded at full resolution, like PNG and JPEG in the IDE's image viewer.

### Removed

- Thumbnails as file icons.
- The Advanced Settings *Maximum decoded image size* and *Show thumbnails as HEIC file icons*.

## [0.1.0] - 2026-09-25

### Added

- HEIC/HEIF files (`.heic`, `.heif`, `.hif`, `.heics`) open in the built-in image viewer and in the side-by-side VCS image diff (macOS only), also in the diff and merge windows started from the command line (`studio diff`, `idea merge`, git difftool/mergetool …) while the IDE is not running.
- Decoding through the HEIF decoder built into macOS via the Java FFM API: EXIF and HEIF (`irot`/`imir`) orientation, transparency, 10-bit and grid images; the primary image of collections and `.heics` sequences.
- Thumbnails of local HEIC files as their icons in the Project view, editor tabs and file lists.
- Advanced Settings (*Settings | Advanced Settings | HEIC Viewer*): maximum decoded image size (default 64 megapixels) and thumbnail icons on/off.
- Truncated files show "Image not loaded" instead of a black image.
- Installs, updates and uninstalls without an IDE restart.
- Keeps the HEIC extensions mapped to the Image file type.

[Unreleased]: https://github.com/ZhuJHua/intellij-heic-viewer/compare/0.1.0...HEAD
[0.1.0]: https://github.com/ZhuJHua/intellij-heic-viewer/commits/0.1.0
