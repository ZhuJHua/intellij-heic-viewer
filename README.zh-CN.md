# HEIC Viewer

[![Build](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml/badge.svg)](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README.md) | 简体中文

为基于 IntelliJ 的 IDE 增加 HEIC/HEIF 图片格式。扩展名为 `.heic`、`.heif`、`.hif`、`.heics` 的文件会在自带的图片查看器和
版本控制（VCS）图片对比中打开，和 PNG、JPEG 一样。

图片由操作系统解码：

- **macOS**：无需安装。
- **Windows 10 和 11**：Microsoft Store 中的 *HEIF 图像扩展*（HEIF Image Extension）和 *HEVC 视频扩展*（HEVC Video
  Extensions）。
- **Linux**：libheif 及其 HEVC 解码器（libde265）。

缺少组件时，图片上方的横幅会说明需要安装什么。

## 截图

![自带图片查看器中的 HEIC 图片](.github/readme/viewer.png)

![HEIC 文件的 VCS 图片对比](.github/readme/diff.png)

## 环境要求

- IntelliJ IDEA、PyCharm、WebStorm、GoLand 等基于 IntelliJ 的 IDE **2024.1** 及更新版本，Android Studio
  **Koala（2024.1.1）** 及更新版本。
- **macOS**：无需安装。
- **Windows 10 或 11**：HEIF 图像扩展和 HEVC 视频扩展，详见 [Windows: HEIF 和 HEVC 扩展](#windows-heif-和-hevc-扩展)。
- **Linux**：libheif（`libheif.so.1`）及其 HEVC 解码器，详见 [Linux: libheif](#linux-libheif)。

### Windows: HEIF 和 HEVC 扩展

从 Microsoft Store 安装这两个扩展：

| 扩展 | Microsoft Store |
|---|---|
| HEIF 图像扩展（HEIF Image Extension） | [9PMMSR1CGPWG](https://apps.microsoft.com/detail/9PMMSR1CGPWG) |
| HEVC 视频扩展（HEVC Video Extensions） | [9NMZLZ57R3T7](https://apps.microsoft.com/detail/9NMZLZ57R3T7) |

部分电脑预装了 *来自设备制造商的 HEVC 视频扩展*（HEVC Video Extensions from Device Manufacturer），它同样可用。安装后，
点击图片上方横幅中的“重新检测”。

### Linux: libheif

安装 libheif 及其 HEVC 解码器。图片上方的横幅会显示适用于当前发行版的命令：

| 发行版 | 命令 |
|---|---|
| Ubuntu 23.10 及更新版本、Debian 13 及更新版本 | `sudo apt install libheif1 libheif-plugin-libde265` |
| Ubuntu 20.04 / 22.04、Debian 11 / 12 | `sudo apt install libheif1` |
| Fedora | `sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm && sudo dnf install libheif-freeworld` |
| RHEL、AlmaLinux、Rocky Linux、CentOS Stream | `sudo dnf install --nogpgcheck https://dl.fedoraproject.org/pub/epel/epel-release-latest-$(rpm -E %rhel).noarch.rpm https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-$(rpm -E %rhel).noarch.rpm && sudo /usr/bin/crb enable && sudo dnf install libheif-freeworld` |
| openSUSE Tumbleweed、Slowroll、Leap | `sudo zypper addrepo -cfp 90 https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/ packman-essentials && sudo zypper --gpg-auto-import-keys refresh packman-essentials && sudo zypper install --from packman-essentials libheif1 libheif-HEIF`（Slowroll 和 Leap 上将 `openSUSE_Tumbleweed` 换成 `openSUSE_Slowroll` 或 `openSUSE_Leap_<版本>`） |
| Arch Linux、Manjaro、EndeavourOS | `sudo pacman -S --needed libheif libde265` |
| Alpine | `sudo apk add libheif` |

安装后，点击图片上方横幅中的“重新检测”。

- 如果 libheif 不在系统的库搜索路径中（例如 NixOS），请在启动 IDE 时把 libheif 所在目录加入 `LD_LIBRARY_PATH`。
- Flatpak 版 IDE 使用其 Flatpak 运行时（freedesktop 25.08 或更新版本）中的 libheif。其 HEVC 解码器需要在宿主系统上用
  `flatpak install flathub org.freedesktop.Platform.codecs-extra//<branch>-extra` 安装。

## 安装

- **JetBrains Marketplace**：<kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > 搜索 “HEIC Viewer” >
  <kbd>Install</kbd>。
- **手动安装**：从 [GitHub Releases](https://github.com/ZhuJHua/intellij-heic-viewer/releases/latest) 下载
  `heic-viewer-<version>.zip`，然后 <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> >
  <kbd>Install Plugin from Disk...</kbd>。

## 开发

构建需要 JDK 25；`testJdk21` 和 `testJdk17` 还需要 JDK 21 和 17。Gradle 会使用已安装的 JDK，或自动下载。

```bash
./gradlew buildPlugin     # build/distributions/heic-viewer-<version>.zip
./gradlew test            # 在 JDK 25 上运行测试
./gradlew check           # 在 JDK 25、21 和 17 上运行测试
./gradlew verifyPlugin    # Plugin Verifier
./gradlew runIde          # 在沙盒 IDE 中运行插件
./gradlew runIdeCanary    # 同上，使用 platformCanaryPath 指定的 IDE
```

构建默认下载 `gradle.properties` 中 `platformType` 和 `platformVersion` 指定的 IDE。要改用已安装的 IDE，在项目根目录创建
`local.properties`：

```properties
# 用于编译、运行和校验的 IDE
platformLocalPath=/Applications/Android Studio.app
# 可选：用于 runIdeCanary 和 verifyPlugin 的第二个 IDE
platformCanaryPath=/Applications/Android Studio Preview.app
# 可选：signPlugin 使用的 chain.crt 和 private_encrypted.pem 所在目录（默认 ~/.jetbrains-sign）
jetbrainsSignDir=/path/to/signing
```

- `HEIC_TEST_LIBHEIF=/path/to/libheif` 让 Linux 后端的测试在任意系统上使用该 libheif。
- 把 `-Dheic.viewer.debug.backendStatus=<REASON>`（例如 `LINUX_LIBHEIF_MISSING`）设为沙盒 IDE 的 VM 选项，可以显示该状态的
  缺少解码器横幅。
- Marketplace 上的插件描述是英文 [README](README.md) 中 `Plugin description` 标记之间的文字；更新说明来自 `CHANGELOG.md`。

## 许可证

[MIT](LICENSE) © 2026 ZhuJHua
