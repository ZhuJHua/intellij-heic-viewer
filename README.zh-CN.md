# HEIC Viewer

[![Build](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml/badge.svg)](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](README.md) | 简体中文

在 Android Studio / IntelliJ 系列 IDE **自带的图片查看器**和**版本控制（VCS）差异对比**中查看 HEIC/HEIF 图片。**目前仅支持 macOS。**

插件把 `.heic`、`.heif`、`.hif`、`.heics` 加入平台自带的 “Image” 文件类型，并注册一个 `javax.imageio` 读取器，通过 Java FFM API
（`java.lang.foreign`，不使用 JNA，不附带任何本地代码）调用 macOS 系统的 ImageIO.framework 解码。

## 功能

- `.heic`、`.heif`、`.hif`、`.heics` 文件在 IDE **自带的图片查看器**中打开，和 PNG 完全一样：缩放、网格、棋盘格背景、
  工具栏右侧的信息标签（例如 `4032x3024 HEIC (24-bit color) 2.36 MB`）。
- **Git/VCS 差异对比**：修改过的 HEIC 文件在 Diff 窗口中显示为 IDE 自带的左右并排图片对比（`TwosideBinaryDiffViewer`，两侧缩放同步）。
  IDE 未运行时从命令行启动的 Diff/合并窗口（`studio diff`、`studio merge`、配置为使用 IDE 的 git difftool/mergetool）同样可以显示。
- 正确处理 **方向**：EXIF orientation 以及 HEIF 的 `irot`/`imir`，手机竖拍的照片显示为正向。
- 支持 **透明通道**（输出非预乘的 `TYPE_INT_ARGB`）、**10 bit** 图片、**网格（tile）图片**、多图文件和 `.heics` 序列（显示主图）。
- 超大图片按**像素预算**在解码时等比缩小（默认 64 百万像素，最长边不超过 16384），可在
  *Settings | Advanced Settings | HEIC Viewer* 中调整。
- **缩略图文件图标**：本地 HEIC 文件（不超过 64 MB）在项目视图（以及编辑器标签页、导航栏、Recent Files 等所有显示文件图标的地方）
  中显示为图片内容的小缩略图，而不是通用的图片图标。缩略图保持宽高比、居中、带一圈很淡的灰色描边，在 Retina 屏幕上按物理像素清晰绘制，
  浅色/深色主题下都适用。解码在后台进行，完成前显示普通的图片图标；可在 Advanced Settings 中关闭。
- 截断的文件显示为“Image not loaded”而不是全黑图片，也不会导致 IDE 崩溃。
- 不需要重启即可安装、更新、卸载（已在 AS 2026.1.4 和 2026.2.2 Canary 1 的沙盒里验证：卸载时类加载器被回收）。
- 不收集、不发送任何数据。

## 截图

内置图片查看器打开 HEIC，项目树里显示 HEIC 缩略图：

![内置查看器中的 HEIC 图片](.github/readme/viewer.png)

Git 中修改过的 HEIC 文件左右对比：

![HEIC 文件的 Git 图片对比](.github/readme/diff.png)

## 环境要求

- **macOS**（在 macOS 26 / Apple Silicon 上验证；代码与 CPU 架构无关）。
- 基于 IntelliJ Platform **261.26222 或更新版本**、运行在 Java 22+ 上的 IDE（FFM API）：Android Studio 2026.1.3（Quail 3）
  及更新版本、IntelliJ IDEA / PyCharm / WebStorm 等 2026.1.4 及更新版本，以及所有 2026.2+ 版本。
  Android Studio 2026.1.1 和 2026.1.2 自带的是 JBR 21，无法加载本插件（Java 22 字节码），因此不在支持范围内。

## 安装

- **JetBrains Marketplace**：<kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > 搜索 “HEIC Viewer” > <kbd>Install</kbd>。
- **手动安装**：从 [GitHub Releases](https://github.com/ZhuJHua/intellij-heic-viewer/releases/latest)（或 JetBrains Marketplace）
  下载 `heic-viewer-<版本>.zip`，然后 <kbd>Settings</kbd>（⌘,）> <kbd>Plugins</kbd> > 齿轮图标 > <kbd>Install Plugin from Disk…</kbd>，
  选择该 zip 即可，无需重启。

## 设置

*Settings | Advanced Settings | HEIC Viewer*：

- **解码图片的最大尺寸（百万像素）**（`heic.viewer.max.megapixels`），默认 64，范围 1–512。超过的图片在解码时等比缩小
  （最长边另外限制为 16384）。IDE 的图片查看器总是请求原始分辨率，而 Diff 会同时解码两张图，这个预算用来限制内存占用。
- **用缩略图作为 HEIC 文件图标**（`heic.viewer.project.view.thumbnails`），默认开启。关闭后所有 HEIC 文件恢复为普通图片图标。
  修改在点击 OK/Apply 后生效：已显示的图标在设置对话框关闭后立即刷新（不需要重新打开项目）。

## 限制与已知问题

- **目前仅支持 macOS**（依赖系统的 ImageIO.framework）。在 Windows / Linux 上可以安装，但插件不做任何事情（也不会把 `.heic` 映射成图片类型）。
  计划在 0.2 版本中通过各系统自带的解码器支持 Windows 和 Linux。
- 颜色统一转换到 **sRGB**：Display P3 照片中超出 sRGB 的颜色会被裁剪。
- **HDR 增益图（gain map）被忽略**，显示的是标准动态范围的基础图像。
- 只显示文件的**主图**；多图文件、`.heics` 序列的其它帧、深度图等辅助图像不显示。
- 超过像素预算的图片会被缩小：编辑器信息标签显示的是解码后的尺寸，而文档弹窗/补全中的尺寸是原始尺寸。
- 截断的文件显示为 “Image not loaded”（日志中有具体原因）；长度完整但压缩数据损坏的文件（位翻转、预分配后未写完的零填充尾部等）
  仍可能显示为全黑，因为 ImageIO.framework 对此不报告任何错误。
- **IJPL-39443**：IDE 在插件被卸载期间保存设置时，会在 `filetypes.xml` 里写入 `<removed_mapping ext="heic" type="Image"/>`。
  插件启动时会把这类“不属于任何文件类型”的扩展名重新关联到 Image（用户显式映射到其它类型的扩展名不会被改动）。
  因此卸载插件后 `filetypes.xml` 中会留下显式的 `<mapping ext="heic" type="Image"/>`（此时 `.heic` 会以图片类型打开但没有读取器，
  可在 *Settings | Editor | File Types* 中删除）。反过来，如果手动把 heic 从 Image 类型中移除（未映射到其它类型），
  插件下次启动时会重新关联；要让 HEIC 不再作为图片打开，请禁用插件。
- 免重启安装时如果已经打开了 HEIC 标签页，需要关闭后重新打开。
- 安装插件之前已被 IDE 索引过的 HEIC 文件，在补全/文档弹窗中的尺寸信息要等文件变化或重建索引后才会出现。
- AVIF 不在本插件处理范围内（AVIF 头会被明确拒绝）。
- 缩略图：`FileIconProvider` 无法区分调用场景，所以缩略图会出现在所有显示文件图标的地方（项目视图、编辑器标签页、导航栏、
  Recent Files、Search Everywhere 等）。非本地文件（jar/归档内、远程、Diff 中的历史版本）和超过 64 MB 的文件使用普通图片图标。
  第一次显示某个文件时会先短暂显示普通图标；一个目录下有大量 HEIC 文件时，缩略图按 2 个线程逐个出现。缓存最多 500 个图标，
  超出后最久未使用的会在需要时重新解码。
- 未验证：Intel Mac、macOS 26 以前的版本、Git LFS 管理的 HEIC。

## 工作原理

1. **文件类型**：`META-INF/heic-viewer-macos.xml` 中的 `<fileType name="Image" extensions="heic;heif;hif;heics"/>`（不带
   `implementationClass`）把扩展名合并到平台已有的 “Image” 文件类型上（和 AS 自带的 WebP 支持同样的做法）。于是
   `ImageFileEditorProvider` 接管编辑器，Diff 中两侧内容也会用图片编辑器显示。
2. **ImageIO 读取器**：IDE 的 `IfsUtil`（编辑器、Diff）和 `ImageInfoReader`（图片信息索引、补全、文档弹窗）都是用
   `ImageIO.getImageReaders(stream)` 按内容嗅探来选读取器的。插件注册 `HeicImageReaderSpi`：
   - `canDecodeInput` 只运行纯 Java 的 `HeifSniffer`（解析 `ftyp` box，最多 512 字节），绝不加载本地代码、绝不抛出异常，
     所以即使本地层出错也不会影响 PNG/JPEG 等其它格式；
   - AVIF（`avif/avis/avio` 品牌）、MP4/MOV 等被明确拒绝；
   - `getFormatName()` 为 `heic`，信息标签显示 `HEIC`；不解码像素即可给出宽高（已考虑方向）。
3. **解码**：`mac/MacImageIO` 用 FFM 绑定 CoreFoundation、ImageIO、CoreGraphics 和 libobjc（首次解码时才初始化）。
   `mac/HeicDecoder` 的流程：`CFDataCreate` → `CGImageSourceCreateWithData(ShouldCache=false)` → 主图属性 →
   `CGImageSourceCreateThumbnailAtIndex(FromImageAlways, WithTransform, ThumbnailMaxPixelSize, ShouldCacheImmediately)`
   → 以约 100 万像素为一条带，经 `CGImageCreateWithImageInRect` 裁剪后绘制到显式的 8 bit **sRGB** 位图上下文 →
   复制到 `BufferedImage`。每次调用都有自己的 autorelease pool 和 confined `Arena`，所有 CF 对象在 `finally` 中释放，线程安全。
   解码前数据必须通过 `HeifSniffer`（ImageIO.framework 按内容选择解码器，其它格式绝不能交给它），再用纯 Java 检查 ISO-BMFF 结构
   （`mac/IsoBoxes`：顶层 box 与 `iloc` 数据区是否完整），因为 ImageIO.framework 会把截断的文件“成功”解码成黑图；
   ImageIO 报告的类型也必须属于 HEIF 家族（`public.heic`、`public.heif` 等）。解码层位于 `HeicBackend` 接口之后，将来的 Windows/Linux 解码器也接在这里。
4. **注册时机**：`AppLifecycleListener.appFrameCreated`（正常启动，在恢复项目/编辑器标签之前）、
   `DynamicPluginListener.pluginLoaded/beforePluginUnload`（免重启安装/更新/卸载），以及 `HeicReaderRegistrar`：一个只针对
   Image 文件类型的 `fileEditorProvider`，它的 `accept` 注册读取器并始终返回 `false`。命令行启动的 Diff/合并窗口（IDE 未运行时执行
   `studio diff a.heic b.heic` 等）不会创建 IDE 主窗口、不会触发 `appFrameCreated`，但在创建图片查看器之前会询问编辑器提供者。
   它从不创建编辑器，所以插件加载/卸载不会影响已打开的编辑器和 Diff 窗口；`beforePluginUnload` 之后不会再注册读取器。
   注册器会移除旧类加载器残留的同名读取器，并通过 `IIORegistry.setOrdering` 让本插件优先于其它插件提供的 HEIF 读取器。
   刻意**不**使用 `ApplicationLoadListener`（内部 API、不支持动态加载）和 262 新增的 `imageReaderWriterSpi` 扩展点（在 261 上会导致
   无法免重启卸载），也**不**提供 `META-INF/services` 条目。
   注册后 `HeicSupport` 会检查 `ImageIO` 本身能否找到该读取器：`IIORegistry.getDefaultInstance()` 不是线程安全的，IDE 启动时若有两个
   线程同时首次调用它，`ImageIO` 可能在整个会话中使用另一个注册表（Android Studio 2026.2 Canary 上曾有一次启动后所有 HEIC 图片都无法
   加载，这是最可能的原因）。此时会通过
   `ImageIO.scanForPlugins()`（配合一个只声明本读取器的上下文类加载器）向 `ImageIO` 的注册表再注册一个实例，卸载时一并移除，
   并在 `idea.log` 中以警告记录这一情况。
5. **IJPL-39443 修复**（`HeicFileTypeMappingRepair`）：插件在启动/加载时检查：如果某扩展名当前不属于任何文件类型，就重新关联到 Image
   （用户显式映射到其它类型的扩展名不会被改动）。
6. **缩略图文件图标**（`thumbnail` 包）：
   - `HeicThumbnailIconProvider`（`com.intellij.fileIconProvider`，`order="first"`，动态扩展点）只对本地
     （`isInLocalFileSystem`）、非空、不超过 64 MB、扩展名为 heic/heif/hif/heics 的文件、且设置开启时工作。`getIcon` **从不解码**：
     只查 LRU 缓存（500 项，键 = URL + VFS 时间戳 + 文件长度 + 图标逻辑尺寸 + 最大屏幕缩放），未命中时把解码任务交给插件自己的
     有界线程池（`AppExecutorUtil.createBoundedApplicationPoolExecutor`，最多 2 个线程，同一个键只排队一次）并返回 `null`，
     平台于是显示 Image 文件类型的默认图标；解码失败会被缓存（文件变化后键也会变化，自动重试）。
   - 后台任务直接从磁盘读取文件（不经过 VFS，避免照片内容进入 VFS 内容缓存；只有文件头是 HEIC/HEIF `ftyp` box 时才读取全文，
     仅仅以 `.heic` 命名的其它格式文件绝不会交给 ImageIO.framework），调用
     `HeicDecoder.decodeThumbnail(bytes, 2 × 最大物理像素尺寸)`（Retina 上为 64，允许使用文件内嵌缩略图），再用纯 Java2D
     渲染成正方形图标：多次对半双线性缩小（避免锯齿）、等比居中、透明背景、1 个物理像素的半透明灰色描边。结果是
     `JBImageIcon` 包着一个 JDK 的 `BaseMultiResolutionImage`（1x 和最大屏幕缩放两个版本），Java2D 按绘制时的设备缩放选择版本，
     所以 Retina 和普通屏幕上都是 1:1 的物理像素。
   - 缩略图就绪后，在 EDT 上（合并为一次 `invokeLater`）对该文件发布公开 API
     `VirtualFileAppearanceListener.fireVirtualFileAppearanceChanged`：平台会清空 `IconDeferrer` 缓存，项目视图
     （`ProjectFileNodeUpdater`）更新该文件的节点，编辑器标签页、导航栏更新显示，再次询问 provider 时即返回缓存中的图标。
   - 为什么不用平台的延迟图标（`IconDeferrer.defer` / `IconManager.createDeferredIcon`）：在 AS 261 的字节码中，
     `IconUtil.getIcon(file)` 本身已经把整个文件图标（包括所有 `FileIconProvider`）包在
     `IconManager.createDeferredIcon(FileIconKey)` 里，在后台的 `readAction { }` 中计算；嵌套的 `IconDeferrerImpl.defer` 在
     `isEvaluationInProgress` 时会**同步**执行求值函数——也就是说解码会在读锁里进行，阻塞写操作。另外 `DeferredIconImpl` 在求值完成前
     一直持有插件的求值 lambda（`evaluator` 字段，`setDone` 时才置空），项目视图中尚未绘制的节点会因此在卸载后继续引用插件的类加载器。
   - **免重启卸载**：交给平台的对象（图标、图片）全部是平台/JDK 类，平台缓存（`IconDeferrer`、`LastComputedIconCache`、
     树节点）不会引用插件的类；`HeicDynamicPluginListener.beforePluginUnload` 调用 `HeicThumbnails.shutDown()`：取消排队中的解码、
     关闭线程池并等待正在运行的解码（最多 1 秒）、清空缓存；排队中的 EDT 任务通过 `expired` 条件失效（`DynamicPlugins` 卸载时会调用 `LaterInvocator.purgeExpiredItems`，
     并清空 `IconDeferrer` 缓存）。设置监听器和 VFS 监听器都是声明式的 `applicationListeners`，由平台自动注销。
   - 切换设置（`AdvancedSettingsChangeListener`）会立即清空缓存，并对所有请求过图标的文件发布外观变化，关闭设置对话框后
     项目视图即切换为缩略图/默认图标，无需重新打开项目。HEIC 文件在磁盘上被修改时（`BulkFileListener`，只比较字符串）同样刷新。
7. **仅 macOS**：所有扩展都放在 `META-INF/heic-viewer-macos.xml` 中，通过
   `<depends optional="true" config-file="heic-viewer-macos.xml">com.intellij.modules.os.mac</depends>` 只在 macOS 上加载，
   其它系统上 `.heic` 不会被映射成没有读取器的图片类型。之所以是可选依赖而不是强制依赖：Android Studio 的 `product-info.json`
   没有声明 `com.intellij.modules.os.mac` 这个别名，Plugin Verifier 无法解析它；IDE 运行时在 macOS 上会自动提供。

## 开发

### 环境

- 解码测试需要 macOS（调用系统的 ImageIO.framework）；其余部分在任何系统上都能构建。
- JDK 25 作为 Gradle toolchain（代码以 `--release 22` 编译）。Gradle 会使用已安装的任意 JDK 25（包括运行 Gradle 本身的 JDK），
  找不到时由 foojay 插件自动下载（复用 IDE 自带 JBR 25 的方法见下文）。
- Gradle 9.4.1（wrapper 已包含），IntelliJ Platform Gradle Plugin 2.19.0，版本统一记录在 `gradle/libs.versions.toml`。

### 使用本地安装的 IDE，而不是下载（`local.properties`）

默认情况下（例如 CI、新克隆的仓库），构建会下载并缓存 `gradle.properties` 中 `platformType` / `platformVersion` 指定的 IDE
（Android Studio 2026.1.4.8，约 1.5 GB）。如果想直接针对本机已安装的 IDE 编译、运行和校验，在项目根目录创建
`local.properties`（已在 `.gitignore` 中忽略，不会提交）：

```properties
# 编译、runIde、verifyPlugin 所用的本地 IDE
platformLocalPath=/Applications/Android Studio.app
# 可选：第二个本地 IDE（如 Canary），用于 runIdeCanary 和 verifyPlugin
platformCanaryPath=/Applications/Android Studio Preview.app
# 可选：本地签名用的 chain.crt + private_encrypted.pem 所在目录（默认 ~/.jetbrains-sign）
#jetbrainsSignDir=/path/to/signing
```

| 属性 | 含义 |
|---|---|
| `platformLocalPath` | 编译、运行（`runIde`）、校验（`verifyPlugin`）所用的本地 IDE；不设置时下载 `platformType`/`platformVersion` |
| `platformCanaryPath` | 可选；存在时用于 `runIdeCanary`，并在设置了 `platformLocalPath` 时一起参与 `verifyPlugin` |
| `jetbrainsSignDir` | 可选；本地签名证书目录（默认 `~/.jetbrains-sign`），密码来自环境变量 `PRIVATE_KEY_PASSWORD` |

同名的 Gradle 属性（`-PplatformLocalPath=...` 或 `~/.gradle/gradle.properties`）优先于 `local.properties`；空值表示禁用，
例如 `./gradlew build -PplatformLocalPath=` 会和 CI 一样下载 IDE 构建。

**复用 IDE 自带的 JBR 25 作为 JDK**：Gradle toolchain 不能从 `local.properties` 配置。要避免 foojay 下载 JDK，可以让 Gradle
运行在该 JBR 上（`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`，或在 IDE 的 Gradle 设置中把
Gradle JVM 选为 IDE 自带的 JBR），或者传入
`-Porg.gradle.java.installations.paths=/Applications/Android Studio.app/Contents/jbr/Contents/Home`（也可以写进
`~/.gradle/gradle.properties`）。

### 构建、测试、运行

```bash
./gradlew buildPlugin        # 生成 build/distributions/heic-viewer-0.1.0.zip
./gradlew test               # JUnit 5 单元/集成测试（解码测试只在 macOS 上运行）
./gradlew verifyPlugin       # Plugin Verifier：本地 IDE（platformLocalPath / platformCanaryPath），否则为 gradle.properties 中的 pluginVerificationIdes
./gradlew runIde             # 在沙盒中启动 IDE 并加载插件
./gradlew runIdeCanary       # 在沙盒中启动第二个本地 IDE（需要 platformCanaryPath）
./gradlew clean buildPlugin test verifyPlugin   # 完整检查
```

`.run/` 目录中的共享运行配置（Run Plugin、Run Tests、Run Verifications）对应上面的任务。

说明：`verifyPluginProjectConfiguration` 会提示 “targetCompatibility 22 高于 261 要求的 21”。这是预期的：FFM 从 Java 22
起才是正式 API，而所有受支持的 IDE（since-build 261.26222）都运行在 JBR 25 上。

### 项目结构

```
.github/                          GitHub Actions（build、release）、Dependabot、Issue 模板
.run/                             共享的 IDE 运行配置
build.gradle.kts, settings.gradle.kts, gradle.properties   Gradle 9.4.1 + IntelliJ Platform Gradle Plugin 2.19.0
gradle/libs.versions.toml         版本目录（IntelliJ Platform Gradle Plugin、Changelog 插件、JUnit）
local.properties                  本机设置（不提交，见上文）
src/main/java/cn/yooss/heic/
  HeifSniffer.java                 纯 Java 的 ftyp 嗅探（不接触本地代码）
  HeicImageReaderSpi.java          javax.imageio 服务提供者（格式名、后缀、MIME、canDecodeInput）
  HeicImageReader.java             读取器：读入流、宽高、图像类型、子采样/源区域/像素预算、异常包装
  HeicBackend.java                 读取器与本地层之间的接口（测试中可替换；将来的 Windows/Linux 解码器也接在这里）
  DecodeLimits.java                像素预算
  HeicSupport.java                 在 IIORegistry 中注册/注销（macOS 检查、排序、清理旧副本、注册表分裂）
  HeicSettings.java                Advanced Settings 读取（失败时回退默认值）
  HeicBundle.java                  资源包 messages/HeicBundle
  HeicFileTypeMappingRepair.java   IJPL-39443 修复
  HeicAppLifecycleListener.java    启动时注册 + 修复
  HeicDynamicPluginListener.java   免重启加载/卸载
  HeicReaderRegistrar.java         命令行 Diff/合并窗口（IDE 未运行时的 studio diff/merge）中注册读取器（从不接受文件的编辑器提供者）
  mac/MacImageIO.java              FFM 绑定（CoreFoundation / ImageIO / CoreGraphics / libobjc）
  mac/HeicDecoder.java             readInfo / decode / decodeThumbnail
  mac/IsoBoxes.java                截断检测（顶层 box + iloc）
  thumbnail/HeicThumbnailIconProvider.java   FileIconProvider（缩略图文件图标）
  thumbnail/HeicThumbnails.java              缓存、后台解码、刷新、卸载时清理（平台相关部分）
  thumbnail/ThumbnailLoader.java             异步去重加载 + LRU 缓存 + 失败缓存（纯 Java）
  thumbnail/ThumbnailRenderer.java           等比居中、渐进缩小、描边、多分辨率图片（纯 Java2D）
  thumbnail/ThumbnailGeometry.java, ThumbnailKey.java, LruCache.java   尺寸计算、缓存键、LRU
  thumbnail/HeicThumbnailSettingsListener.java, HeicThumbnailFileListener.java   设置切换 / 文件修改时刷新图标
src/main/resources/
  META-INF/plugin.xml              插件 id、名称、vendor、依赖（description 和 change-notes 由 Gradle 从 README.md / CHANGELOG.md 生成）
  META-INF/heic-viewer-macos.xml   插件的全部扩展（只在 macOS 上加载）
  META-INF/pluginIcon.svg, pluginIcon_dark.svg
  messages/HeicBundle.properties, HeicBundle_zh_CN.properties
src/test/java/...                  JUnit 5 测试（嗅探、预算、解码、截断、ImageIO 集成、注册、缩略图缓存/几何/渲染）
src/test/resources/fixtures/       合成测试图片（全部由脚本生成，不含个人照片）
src/test/fixture-generators/       测试图片的生成源码与说明
CHANGELOG.md                       Keep a Changelog 格式；每个版本的 change notes 由它生成
```

### 插件描述与更新日志

- Marketplace / 插件管理器中显示的插件描述来自 `README.md` 中 `<!-- Plugin description -->` 与 `<!-- Plugin description end -->`
  之间的英文 Markdown（构建时转换为 HTML 写入 `plugin.xml`）。修改插件描述请改那一段。
- change notes 来自 `CHANGELOG.md`：当前版本的小节，若不存在则为 `## [Unreleased]`。新的改动写在 `## [Unreleased]` 下。

### 持续集成与发布

- `.github/workflows/build.yml`：每次推送到 `main` 和每个 Pull Request 时运行。在 `macos-latest` 上构建并运行测试
  （解码测试需要 macOS），在 `ubuntu-latest` 上针对 `pluginVerificationIdes` 运行 Plugin Verifier，推送到 `main` 时再根据
  `CHANGELOG.md` 的 `[Unreleased]` 小节创建一个 GitHub Release 草稿。
- 在 GitHub 上发布该草稿会触发 `.github/workflows/release.yml`：把发布说明写入 `CHANGELOG.md` 的版本小节、签名并发布到
  JetBrains Marketplace、把 zip 附加到 Release，并创建一个更新 changelog 的 Pull Request。需要的仓库 Secrets：
  `PUBLISH_TOKEN`、`CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`
  （参见 [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)）。
  另外需在仓库 Settings | Actions | General | Workflow permissions 中勾选 Allow GitHub Actions to create and approve pull
  requests（个人仓库默认关闭），否则发布流程最后一步无法创建 changelog PR（流程会输出一条警告），需要手动从已推送的
  `changelog-update-<version>` 分支创建 PR。请在下一次发布前合并它，否则下一个 Release 草稿会重复已发布的条目。
- 每次发布前提高 `gradle.properties` 中的 `pluginVersion`。
- 第一次发布（0.1.0）：第一次上传 JetBrains Marketplace 必须手动完成。先在本地构建并签名
  （`PRIVATE_KEY_PASSWORD=... ./gradlew signPlugin`），在 JetBrains Marketplace 上传
  `build/distributions/heic-viewer-0.1.0-signed.zip`（Upload plugin），然后再发布 0.1.0 的 Release 草稿。Release 流程对 0.1.0
  跳过 Marketplace 上传，但仍会把 zip 附加到 Release 并创建 changelog PR。之后每次发布只需提高 `pluginVersion` 并发布草稿。
- 本地签名：`PRIVATE_KEY_PASSWORD=... ./gradlew signPlugin verifyPluginSignature` 使用环境变量 `CERTIFICATE_CHAIN` /
  `PRIVATE_KEY`；没有这两个环境变量时，使用 `jetbrainsSignDir`（默认 `~/.jetbrains-sign`）中的 `chain.crt` + `private_encrypted.pem`。
  两者都没有时 `signPlugin` 被跳过，不影响其它任务。

## 路线图

- 0.2：通过各系统自带的解码器支持 Windows 和 Linux（实现放在现有的 `HeicBackend` 接口之后）。

## 许可证

[MIT](LICENSE) © 2026 ZhuJHua
