<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# HEIC Viewer Changelog

## [Unreleased]

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
