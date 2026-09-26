# HEIC Viewer

[![Build](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml/badge.svg)](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [简体中文](README.zh-CN.md)

<!-- Plugin description -->
Adds the HEIC/HEIF image format to IntelliJ-based IDEs. Files with the extensions `.heic`, `.heif`, `.hif` and `.heics`
open in the built-in image viewer and in the VCS image diff, just like PNG and JPEG.

The images are decoded by the operating system:

- **macOS**: nothing to install.
- **Windows 10 and 11**: the *HEIF Image Extension* and the *HEVC Video Extensions* from the Microsoft Store.
- **Linux**: libheif with its HEVC decoder (libde265).

If something is missing, a banner above the image says what to install.

[Source code and issue tracker](https://github.com/ZhuJHua/intellij-heic-viewer)
<!-- Plugin description end -->

## Screenshots

![HEIC image in the built-in image viewer](.github/readme/viewer.png)

![VCS image diff of a HEIC file](.github/readme/diff.png)

## Requirements

- IntelliJ IDEA, PyCharm, WebStorm, GoLand and the other IntelliJ-based IDEs **2024.1** or newer, Android Studio
  **Koala (2024.1.1)** or newer.
- **macOS**: nothing to install.
- **Windows 10 or 11**: the HEIF Image Extension and the HEVC Video Extensions, see
  [Windows: HEIF and HEVC extensions](#windows-heif-and-hevc-extensions).
- **Linux**: libheif (`libheif.so.1`) with its HEVC decoder, see [Linux: libheif](#linux-libheif).

### Windows: HEIF and HEVC extensions

Install both extensions from the Microsoft Store:

| Extension | Microsoft Store |
|---|---|
| HEIF Image Extension | [9PMMSR1CGPWG](https://apps.microsoft.com/detail/9PMMSR1CGPWG) |
| HEVC Video Extensions | [9NMZLZ57R3T7](https://apps.microsoft.com/detail/9NMZLZ57R3T7) |

Some PCs come with the *HEVC Video Extensions from Device Manufacturer*, which work as well. After installing, click
*Check Again* in the banner above the image.

### Linux: libheif

Install libheif with its HEVC decoder. The banner above the image shows the command for your distribution:

| Distribution | Command |
|---|---|
| Ubuntu 23.10 and newer, Debian 13 and newer | `sudo apt install libheif1 libheif-plugin-libde265` |
| Ubuntu 20.04 / 22.04, Debian 11 / 12 | `sudo apt install libheif1` |
| Fedora | `sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm && sudo dnf install libheif-freeworld` |
| RHEL, AlmaLinux, Rocky Linux, CentOS Stream | `sudo dnf install --nogpgcheck https://dl.fedoraproject.org/pub/epel/epel-release-latest-$(rpm -E %rhel).noarch.rpm https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-$(rpm -E %rhel).noarch.rpm && sudo /usr/bin/crb enable && sudo dnf install libheif-freeworld` |
| openSUSE Tumbleweed, Slowroll, Leap | `sudo zypper addrepo -cfp 90 https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/ packman-essentials && sudo zypper --gpg-auto-import-keys refresh packman-essentials && sudo zypper install --from packman-essentials libheif1 libheif-HEIF` (with `openSUSE_Slowroll` or `openSUSE_Leap_<version>` instead of `openSUSE_Tumbleweed` on Slowroll and Leap) |
| Arch Linux, Manjaro, EndeavourOS | `sudo pacman -S --needed libheif libde265` |
| Alpine | `sudo apk add libheif` |

After installing, click *Check Again* in the banner above the image.

- If libheif is not in the system's library path (e.g. on NixOS), start the IDE with libheif's directory in
  `LD_LIBRARY_PATH`.
- A Flatpak IDE uses the libheif of its Flatpak runtime (freedesktop 25.08 or newer). Its HEVC decoder is installed on
  the host with `flatpak install flathub org.freedesktop.Platform.codecs-extra//<branch>-extra`.

## Installation

- **JetBrains Marketplace**: <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for
  "HEIC Viewer" > <kbd>Install</kbd>.
- **Manually**: download `heic-viewer-<version>.zip` from
  [GitHub Releases](https://github.com/ZhuJHua/intellij-heic-viewer/releases/latest), then <kbd>Settings</kbd> >
  <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>.

## Development

The build needs JDK 25, and JDK 21 and 17 for `testJdk21` and `testJdk17`; Gradle uses installed JDKs or downloads
them.

```bash
./gradlew buildPlugin     # build/distributions/heic-viewer-<version>.zip
./gradlew test            # tests on JDK 25
./gradlew check           # tests on JDK 25, 21 and 17
./gradlew verifyPlugin    # Plugin Verifier
./gradlew runIde          # sandboxed IDE with the plugin
./gradlew runIdeCanary    # the same in the IDE at platformCanaryPath
```

The build downloads the IDE set by `platformType` and `platformVersion` in `gradle.properties`. To use installed IDEs
instead, create `local.properties` in the project root:

```properties
# IDE to compile against, run and verify with
platformLocalPath=/Applications/Android Studio.app
# Optional: second IDE for runIdeCanary and verifyPlugin
platformCanaryPath=/Applications/Android Studio Preview.app
# Optional: directory with chain.crt and private_encrypted.pem for signPlugin (default ~/.jetbrains-sign)
jetbrainsSignDir=/path/to/signing
```

- `HEIC_TEST_LIBHEIF=/path/to/libheif` runs the Linux backend tests against that libheif, on any OS.
- `-Dheic.viewer.debug.backendStatus=<REASON>` (e.g. `LINUX_LIBHEIF_MISSING`) as a VM option of the sandboxed IDE shows
  the missing-decoder banner for that status.
- The Marketplace description is the text between the `Plugin description` markers above; the change notes come from
  `CHANGELOG.md`.

## License

[MIT](LICENSE) © 2026 ZhuJHua
