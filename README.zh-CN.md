# HEIC Viewer

[![Build](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml/badge.svg)](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README.md) | 简体中文

为 IDE 增加 HEIC/HEIF 图片格式。扩展名为 `.heic`、`.heif`、`.hif`、`.heics` 的文件会在 IDE **自带的图片查看器**和
**版本控制（VCS）图片差异对比**中打开，和 PNG、JPEG 一样。图片由操作系统自己的 HEIF 解码器解码：

- **macOS**：系统自带，无需安装。
- **Windows 10 和 11**：Microsoft Store 中的 *HEIF 图像扩展*（HEIF Image Extension）和 *HEVC 视频扩展*（HEVC Video
  Extensions），部分电脑已预装两者。
- **Linux**：发行版软件包中的 libheif 及其 HEVC 解码器（libde265）。

缺少组件时，图片上方的横幅会说明需要安装什么；安装后点击“重新检测”即可显示图片，无需重启 IDE。

插件不附带任何解码器或本地代码，不发送任何数据；安装、更新、卸载都无需重启 IDE。

## 截图

在自带的图片查看器中打开 HEIC 文件：

![内置查看器中的 HEIC 图片](.github/readme/viewer.png)

修改过的 HEIC 文件的左右并排图片对比：

![HEIC 文件的 Git 图片对比](.github/readme/diff.png)

## 环境要求

- 基于 IntelliJ Platform **241.14494 或更新版本**的 IDE：IntelliJ IDEA / PyCharm / WebStorm / GoLand 等 **2024.1** 及更新版本，
  **Android Studio Koala**（2024.1.1）及更新版本。
- **macOS**（Apple Silicon 或 Intel）：无需安装任何东西。
- **Windows 10（1809 或更新）/ 11**（x64 或 arm64）：Microsoft Store 中的 *HEIF 图像扩展*和 *HEVC 视频扩展*，详见
  [Windows: HEIF 和 HEVC 扩展](#windows-heif-和-hevc-扩展)。
- **Linux**（x64 或 arm64）：libheif 1.x（`libheif.so.1`）及其 HEVC 解码器（libde265），详见 [Linux: libheif](#linux-libheif)。

## 安装

- **JetBrains Marketplace**：<kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > 搜索 “HEIC Viewer” >
  <kbd>Install</kbd>。
- **手动安装**：从 [GitHub Releases](https://github.com/ZhuJHua/intellij-heic-viewer/releases/latest)（或 JetBrains
  Marketplace）下载最新的 `heic-viewer-<version>.zip`，然后 <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> >
  <kbd>Install Plugin from Disk...</kbd>。

### Windows: HEIF 和 HEVC 扩展

Windows 上的 HEIC 图片由 Windows 图像处理组件（WIC）借助 Microsoft Store 中的两个软件包解码：

| 软件包 | Store ID | 提供 |
|---|---|---|
| *HEIF 图像扩展*（HEIF Image Extension） | [9PMMSR1CGPWG](https://apps.microsoft.com/detail/9PMMSR1CGPWG) | HEIF 解码器（免费） |
| *HEVC 视频扩展*（HEVC Video Extensions） | [9NMZLZ57R3T7](https://apps.microsoft.com/detail/9NMZLZ57R3T7) | HEIC 照片使用的 HEVC 编解码器 |
| *来自设备制造商的 HEVC 视频扩展*（HEVC Video Extensions from Device Manufacturer） | [9N4WGH0Z6VHQ](https://apps.microsoft.com/detail/9N4WGH0Z6VHQ) | 同一编解码器，部分电脑预装 |

缺少其中之一时，HEIC 图片上方的横幅会说明缺少哪一个，并提供“打开 Microsoft Store”和“重新检测”；“更多”中还有商店网页、
“了解详情”（本节），以及 HEIF 图像扩展的 `winget` 安装命令。安装后点击“重新检测”，或切换回 IDE，即可显示 HEIC 图片。如果刚安装后
仍提示缺少扩展，请重启 IDE。

- 在 PowerShell 中运行 `Get-AppxPackage *HEIFImageExtension*; Get-AppxPackage *HEVCVideoExtension*` 可以查看已安装的扩展。
- Windows “N” 版本需要先安装 *Media Feature Pack*（设置 > 应用 > 可选功能）。
- 没有 Microsoft Store 的版本（Windows Server、LTSC）无法从商店获得 HEVC 编解码器。

### Linux: libheif

Linux 上的 HEIC 图片由系统的 libheif（`libheif.so.1`）及其 HEVC 解码器 libde265 解码。缺少其中之一时，HEIC 图片上方的横幅会
显示当前发行版的安装命令，并提供“复制命令”；安装后点击“重新检测”，或切换回 IDE，即可显示 HEIC 图片。

| 发行版 | 命令 |
|---|---|
| Ubuntu 23.10 及更新版本、Debian 13 及更新版本，以及它们的衍生版 | `sudo apt install libheif1 libheif-plugin-libde265` |
| Ubuntu 20.04 / 22.04、Debian 11 / 12，以及它们的衍生版 | `sudo apt install libheif1` |
| Fedora（HEVC 解码器来自 RPM Fusion Free） | `sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm && sudo dnf install libheif-freeworld` |
| RHEL、AlmaLinux、Rocky Linux、CentOS Stream（libheif 来自 EPEL，HEVC 解码器来自 RPM Fusion Free） | `sudo dnf install --nogpgcheck https://dl.fedoraproject.org/pub/epel/epel-release-latest-$(rpm -E %rhel).noarch.rpm https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-$(rpm -E %rhel).noarch.rpm && sudo /usr/bin/crb enable && sudo dnf install libheif-freeworld` |
| openSUSE Tumbleweed、Slowroll、Leap（HEVC 解码器来自 Packman Essentials） | `sudo zypper addrepo -cfp 90 https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/ packman-essentials && sudo zypper --gpg-auto-import-keys refresh packman-essentials && sudo zypper install --from packman-essentials libheif1 libheif-HEIF`（其它版本分别为 `openSUSE_Slowroll`、`openSUSE_Leap_15.6` 等） |
| Arch Linux、Manjaro、EndeavourOS 等 | `sudo pacman -S --needed libheif libde265` |
| Alpine | `sudo apk add libheif` |

已安装 libheif 但缺少 HEVC 解码器时，横幅只要求安装解码器，例如 Debian 和 Ubuntu 上的
`sudo apt install libheif-plugin-libde265`，Alpine 上的 `sudo apk add libheif-libde265`。

- libheif 以 `libheif.so.1` 的名称通过系统的库搜索路径加载。libheif 不在该路径中时（例如 NixOS，或自行编译的 libheif），
  请在启动 IDE 时把它所在的目录加入 `LD_LIBRARY_PATH`。
- **Flatpak** 版本的 IDE 只能使用其 Flatpak 运行时中的库。freedesktop 运行时 25.08 及更新版本包含 libheif，其 HEVC 解码器是
  `org.freedesktop.Platform.codecs-extra` 扩展，Flatpak 会随运行时一起安装。缺少该扩展时，横幅会显示
  `flatpak install flathub org.freedesktop.Platform.codecs-extra//<branch>-extra`；请在宿主机的终端中运行，不要用 IDE 自带的终端。
  更早的运行时无法解码 HEIC：请使用通过 JetBrains Toolbox App、tar 包或 Snap 安装的 IDE。
- `idea.log` 中以 `HEIC decoder:` 开头的一行说明找到了哪个 libheif，或尝试加载了哪些库。

## 限制

- 没有系统解码器时（见[环境要求](#环境要求)），HEIC 文件仍作为图片打开，但显示“图像未加载”；图片上方的横幅（差异对比则是每个会话
  最多一次的通知）会说明需要安装什么。安装后，已经打开的差异对比需要重新打开。
- 只显示文件的**主图**（不显示图片集合中的其它图片、`.heics` 序列的其它帧、深度图等）。
- 颜色转换为 **sRGB**；**忽略 HDR 增益图**（显示标准动态范围的图像）。
- 和 PNG、JPEG 一样按原始分辨率解码，显示期间每个像素占用 IDE 内存 4 字节。超过约 21 亿像素的图片显示“图像未加载”；在 Linux 上，
  超过约 2.68 亿像素的图片也是如此。
- 截断的文件显示“图像未加载”。长度完整但压缩数据损坏的文件可能显示为黑色。
- 安装插件时已经打开的 HEIC 编辑器标签页需要重新打开。
- 在安装插件之前已建立索引的 HEIC 文件，只有在文件改变或重建索引之后，补全和文档弹窗中才会显示图片尺寸。
- 卸载插件后，`.heic` 等扩展名可能仍关联到 Image 文件类型（*设置 | 编辑器 | 文件类型*）。如果不想让 HEIC 文件作为图片打开，请禁用插件。
- 不处理 AVIF 文件。
- **Windows**：8 bit 单色 HEIC 图片解码为黑色。
- **Linux**：libheif 使用软件解码，大图片比 macOS 上慢。方向来自 HEIF 变换（`irot`、`imir`），单独的 EXIF 方向会被忽略。
  libheif 1.6（Ubuntu 20.04）无法解码带透明通道或每通道 10 bit 的图片。

## 开发

### 环境

- 可在 macOS、Windows 和 Linux 上构建和测试。解码测试在有系统解码器的环境中运行，其余测试在所有环境中运行。Linux 后端的测试
  在 macOS 上也会使用 Homebrew 的 libheif（`brew install libheif`）运行，或使用 `HEIC_TEST_LIBHEIF=/path/to/libheif` 指定的 libheif。
- 以 JDK 25 作为 Gradle 工具链（代码使用 `--release 17` 编译），`testJdk21` / `testJdk17` 还需要 JDK 21 和 JDK 17。Gradle 会使用
  已安装的 JDK（包括运行 Gradle 的 JDK），否则由 foojay resolver 下载。
- Gradle 9.4.1（已包含 wrapper），IntelliJ Platform Gradle Plugin 2.19.0。

### 使用本地安装的 IDE，而不是下载（`local.properties`）

没有其它设置时，构建会下载并缓存 `gradle.properties` 中 `platformType` / `platformVersion` 指定的 IDE。要改用已安装的 IDE 编译、
运行和验证，请在项目根目录创建 `local.properties`（已被 git 忽略）：

```properties
# 编译、runIde、verifyPlugin 所用的本地 IDE
platformLocalPath=/Applications/Android Studio.app
# 可选：第二个本地 IDE，用于 runIdeCanary 和 verifyPlugin
platformCanaryPath=/Applications/Android Studio Preview.app
# 可选：本地签名用的 chain.crt + private_encrypted.pem 所在目录（默认 ~/.jetbrains-sign）
#jetbrainsSignDir=/path/to/signing
```

同名的 Gradle 属性（`-PplatformLocalPath=...`，或写在 `~/.gradle/gradle.properties` 中）优先。空值表示禁用该设置，例如
`./gradlew build -PplatformLocalPath=`。

要使用 IDE 自带的 JBR 25 作为工具链，请用它运行 Gradle
（`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`），或传入
`-Porg.gradle.java.installations.paths=/Applications/Android Studio.app/Contents/jbr/Contents/Home`。

### 构建、测试、运行

```bash
./gradlew buildPlugin      # build/distributions/heic-viewer-<version>.zip
./gradlew test             # 在 JDK 25 上运行 JUnit 5 测试，包括轻量 IDE 测试
./gradlew testJdk21        # 在 JDK 21 上运行同样的测试（IDE 2024.2 - 2026.1.2 的运行时），不含轻量 IDE 测试
./gradlew testJdk17        # 在 JDK 17 上运行同样的测试（IntelliJ 2024.1），不含标记为 "platform" 的测试
./gradlew check            # test、testJdk21 和 testJdk17
./gradlew testJdk25        # 作为普通 Test 任务的 `test`，不含轻量 IDE 测试
./gradlew verifyPlugin     # Plugin Verifier：本地 IDE，或 gradle.properties 中的 pluginVerificationIdes
./gradlew runIde           # 带插件的沙盒 IDE
./gradlew runIdeCanary     # 第二个沙盒 IDE（需要 platformCanaryPath）
./gradlew clean buildPlugin test testJdk21 testJdk17 verifyPlugin   # CI 检查的全部内容
```

`.run/` 中的运行配置（Run Plugin、Run Tests、Run Verifications）封装了同样的任务。

`verifyPluginProjectConfiguration` 会提示 since-build 241 低于编译所用的平台版本，以及 Java 17 低于该平台要求的 Java 版本；
两者都是有意为之。

编译所用 IDE 的平台 jar 是 Java 21 字节码，因此 `testJdk17` 使用最小的类路径（插件 jar、JUnit 和 IDE 的 `util-8.jar`，其中包含
JNA）。需要其它 IDE 类的测试标记为 `platform`，只在 JDK 21 和 25 上运行。`*PlatformIntegrationTest` 类会启动轻量 IDE，只在
`test` 中运行。在 Intel Mac 上，各测试任务依次运行。

### 项目结构

```
.github/                      GitHub Actions 工作流（build、cross-platform、release）、Dependabot、issue 模板
.run/                         共享的 IDE 运行配置
gradle/libs.versions.toml     版本目录（IntelliJ Platform Gradle Plugin、Changelog 插件、JUnit）
src/main/java/cn/yooss/heic/
  HeifSniffer                 纯 Java 的 ftyp 识别（从不调用本地代码）
  HeicImageReaderSpi          javax.imageio 服务提供者（格式名、后缀、MIME 类型、canDecodeInput）
  HeicImageReader             读取器：输入、尺寸、图像类型、子采样/源区域
  HeicSupport                 在 IIORegistry 中注册
  HeicBundle                  资源包
  HeicFileTypeMappingRepair   保持 HEIC 扩展名关联到 Image 文件类型
  HeicAppLifecycleListener, HeicDynamicPluginListener   启动时 / 动态加载和卸载时注册
  InheritedContexts           卸载前：让插件代码启动的线程释放插件类加载器（Java 17-23）
  HeicReaderRegistrar         命令行 Diff 和合并窗口中的注册（一个从不接受文件的编辑器提供者）
  backend/                    HeifBackend、HeifBackendStatus、HeifBackends（按操作系统选择）、AbstractHeifBackend、
                              HeifImageInfo、HeifInput + IsoBoxes（输入检查）、PixelPipeline、PlaneConverter、
                              UnavailableHeifBackend、HeifRemedy + HeifRemedies（每种不可用状态下用户能做什么）；
                              jna/JnaLibraries（JNA 规则、库加载）
  mac/                        MacHeifBackend、HeicDecoder（ImageIO.framework）、MacApi、jna/JnaMacApi
  win/                        WicHeifBackend、WicDecoder（WIC）、WicProbe（可用性）、WinApi、jna/JnaWinApi、
                              Guids、Hresult、WindowsCodecs（Store 产品 ID）、SingleImageGrid + NclxTransfer（颜色）
  linux/                      LibheifHeifBackend（探测）、Libheif（通过 JNA 调用的 libheif C API）、LibheifDecoder、
                              LinuxDistribution + LibheifRemedy（os-release、安装命令）
  ui/                         缺少解码器时：编辑器横幅（HeicDecoderNotificationProvider、HeicFileOpenedListener）、
                              通知（DecoderPrompt）、状态与重新检测（DecoderStatus）、刷新视图（HeicViews）、
                              补救操作、HeicDiffExtension、HeicActivationListener
src/main/resources/
  META-INF/plugin.xml         插件 ID、名称、供应商、依赖以及插件提供的全部内容（描述和更新日志由 Gradle 生成）
  META-INF/pluginIcon.svg, pluginIcon_dark.svg
  messages/HeicBundle.properties, HeicBundle_zh_CN.properties
src/test/java/                JUnit 5 测试；HeifBackendContractTest 是每个后端都必须通过的契约
src/test/resources/fixtures/  合成的测试图片（生成的，不含个人照片）
src/test/fixture-generators/  生成测试图片的源码和说明
CHANGELOG.md                  Keep a Changelog 格式；每个版本的更新说明由它生成
```

### 实现解码后端

- 继承 `backend.AbstractHeifBackend`，参考 `mac.MacHeifBackend`、`win.WicHeifBackend` 和 `linux.LibheifHeifBackend`；
  `HeifBackends` 按操作系统选择后端。
- `probe()`：通过 `backend.jna.JnaLibraries` 加载系统库，返回 `HeifBackendStatus.available(...)`，或带 `withInstallUrl` /
  `withInstallCommand` 的 `unavailable(Reason, detail)`。
- 用户看到的内容是 `backend.HeifRemedies` 中该原因的补救方案：两个消息资源包中的 `remedy.title.<REASON>` 和
  `backend.status.<REASON>`，以及对应的操作。`HeifRemediesTest` 检查每个补救方案。
- `doReadInfo` / `doDecode`：输入检查、可用性检查和本地错误的包装由基类完成。用 `PixelPipeline` 生成图像（8 bit sRGB、
  非预乘 alpha、已应用方向）。
- 遵守 `JnaLibraries` 中的 JNA 规则；`BytecodeLevelTest` 和 `PluginClassLoaderLeakTest` 会检查。
- `HeifBackendContractTest` 必须在该操作系统上通过；在 `.github/workflows/cross-platform.yml` 中把每个任务的 `expect` 设置为
  该任务必须报告的状态（`available`，或例如 `LINUX_LIBHEIF_MISSING`）。
- `-Dheic.viewer.debug.backendStatus=<REASON>[|<url>[|<command>]]` 可在任何操作系统上显示任意状态的横幅和通知（加上
  `-Dheic.viewer.debug.backendStatus.recover=true` 后，“重新检测”会找到真正的解码器）。

### 插件描述与更新日志

- JetBrains Marketplace 和 IDE 插件管理器中显示的描述是 [README.md](README.md) 顶部 `<!-- Plugin description -->` 与
  `<!-- Plugin description end -->` 之间的 Markdown；构建时转换为 HTML 写入 `plugin.xml`。描述必须使用英文。
- 更新日志来自 `CHANGELOG.md`（[Keep a Changelog](https://keepachangelog.com) 格式）：当前版本的小节，没有时使用
  `## [Unreleased]`。新条目写在 `## [Unreleased]` 下。

### 持续集成与发布

- `.github/workflows/build.yml` 在每次推送到 `main` 和每个 pull request 时运行：构建和测试（`check`）、针对
  `pluginVerificationIdes` 的 Plugin Verifier，以及推送到 `main` 时根据 `CHANGELOG.md` 的 `[Unreleased]` 小节生成 GitHub 草稿发布。
- `.github/workflows/cross-platform.yml` 在推送到 `dev/**` 分支、向 `main` 发起 pull request 以及手动触发时运行：在 macOS、
  Windows 和 Linux 上（有和没有系统解码器）构建并在 JDK 25、21、17 上运行测试，运行轻量 IDE 测试和 Plugin Verifier，以及
  *Linux install commands* 任务：在多个发行版的容器中运行插件建议的安装命令，并检查之后能找到 libheif 并正确解码。每个任务都把
  `HEIC_EXPECT_BACKEND` 设置为 `HeifBackendContractTest` 必须看到的解码器状态。
- 发布草稿会触发 `.github/workflows/release.yml`：把发布说明移入 `CHANGELOG.md` 的版本小节，签名并发布插件到 JetBrains
  Marketplace，把 zip 附加到发布，并用更新后的更新日志创建 pull request。需要的仓库 secrets：`PUBLISH_TOKEN`、
  `CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`
  （见 [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)）。创建 pull request 需要开启
  *Settings | Actions | General | Workflow permissions | Allow GitHub Actions to create and approve pull requests*；
  未开启时，请从推送的 `changelog-update-<version>` 分支手动创建。
- 每次发布都要提升 `gradle.properties` 中的 `pluginVersion`。
- 本地签名：`PRIVATE_KEY_PASSWORD=... ./gradlew signPlugin verifyPluginSignature` 使用环境变量 `CERTIFICATE_CHAIN` /
  `PRIVATE_KEY`，否则使用 `jetbrainsSignDir`（默认 `~/.jetbrains-sign`）中的 `chain.crt` + `private_encrypted.pem`。都没有时跳过
  `signPlugin`。

## 许可证

[MIT](LICENSE) © 2026 ZhuJHua
