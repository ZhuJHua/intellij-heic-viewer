# HEIC Viewer

[![Build](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml/badge.svg)](https://github.com/ZhuJHua/intellij-heic-viewer/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/cn.yooss.heic-viewer.svg)](https://plugins.jetbrains.com/plugin/index?xmlId=cn.yooss.heic-viewer)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [简体中文](README.zh-CN.md)

<!-- Plugin description -->
Opens HEIC/HEIF images in the IDE's built-in image viewer and VCS image diff on macOS, Windows and Linux, decoded by the
operating system's own HEIF decoder: built into macOS, the *HEIF Image Extension* and *HEVC Video Extensions* from the
Microsoft Store on Windows 10 and 11, and libheif with its HEVC decoder (libde265) on Linux.

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

The system decoder is called through the JNA library that comes with the IDE. The plugin bundles no decoder and no
native code, and does not send any data.

- **macOS**: nothing to install (ImageIO.framework).
- **Windows**: the *HEIF Image Extension* (free) and the *HEVC Video Extensions* (paid) from the Microsoft Store; some
  PCs come with both.
- **Linux**: libheif 1.x with its HEVC decoder (libde265) from the distribution's packages.

If a component is missing, a banner above the image says what to install, with a link to the Microsoft Store or the
install command for your Linux distribution; *Check Again* then shows the images without restarting the IDE. The pixel
limit and the thumbnails can be changed in *Settings | Advanced Settings | HEIC Viewer*.

Limitations: only the primary image of a file is shown, colors are converted to sRGB, and HDR gain maps are ignored.

[Source code and issue tracker](https://github.com/ZhuJHua/intellij-heic-viewer)
<!-- Plugin description end -->

## Screenshots

A HEIC file in the built-in image viewer, with HEIC thumbnails in the Project view:

![HEIC image in the built-in viewer](.github/readme/viewer.png)

Side-by-side image diff of a modified HEIC file:

![Git image diff of a HEIC file](.github/readme/diff.png)

## Requirements

- An IntelliJ-based IDE on IntelliJ Platform **241.14494 or newer**: IntelliJ IDEA / PyCharm / WebStorm / GoLand / …
  **2024.1** or newer, **Android Studio Koala** (2024.1.1) or newer. The plugin runs on the IDE's own Java runtime
  (JBR 17 in 2024.1, JBR 21 in 2024.2 – 2026.1.2, JBR 25 since 2026.1.3).
- **macOS**, Apple silicon or Intel: nothing to install (ImageIO.framework is part of macOS). Tested on macOS 26 on
  Apple silicon; the decoder tests also run on Intel Macs in CI.
- **Windows 10 (1809 or newer) or 11**, x64 or arm64: the *HEIF Image Extension* (free) and the *HEVC Video Extensions*
  (paid) from the Microsoft Store; some PCs come with both. The plugin links to the one that is missing; see
  [Windows: HEIF and HEVC extensions](#windows-heif-and-hevc-extensions).
- **Linux**, x64 or arm64: libheif 1.x (`libheif.so.1`) with its HEVC decoder (libde265) from the distribution's
  packages, for example `sudo apt install libheif1 libheif-plugin-libde265` on Ubuntu 24.04. The plugin shows the
  install command for the distribution when they are missing; see [Linux: libheif](#linux-libheif) for the commands of
  other distributions. Tested with libheif 1.12 to 1.23; the libheif 1.6 of Ubuntu 20.04 shows photos, but not images
  with transparency or 10 bits per channel.

## Installation

- **JetBrains Marketplace**: <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for
  "HEIC Viewer" > <kbd>Install</kbd>.
- **Manually**: download the latest `heic-viewer-<version>.zip` from
  [GitHub Releases](https://github.com/ZhuJHua/intellij-heic-viewer/releases/latest) (or from JetBrains Marketplace),
  then <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>.
  No restart is needed.

### Windows: HEIF and HEVC extensions

On Windows, HEIC images are decoded by the Windows Imaging Component (WIC) with two packages from the Microsoft Store:

| Package | Store ID | Price | Provides |
|---|---|---|---|
| *HEIF Image Extension* | [9PMMSR1CGPWG](https://apps.microsoft.com/detail/9PMMSR1CGPWG) | free | the HEIF decoder of WIC (Windows 10 1809 or newer) |
| *HEVC Video Extensions* | [9NMZLZ57R3T7](https://apps.microsoft.com/detail/9NMZLZ57R3T7) | paid (US$0.99 in the US Store) | the HEVC codec of HEIC photos |
| *HEVC Video Extensions from Device Manufacturer* | [9N4WGH0Z6VHQ](https://apps.microsoft.com/detail/9N4WGH0Z6VHQ) | free, preinstalled by PC makers (not offered for purchase) | the same codec |

If one is missing, the banner above a HEIC image names it, with *Open Microsoft Store* and *Check Again*; under *More*
are the Store's web page (for systems without the Store app), *Learn More* (this section) and, for the HEIF Image
Extension, `winget install --id 9PMMSR1CGPWG --source msstore --accept-package-agreements` to copy. After installing,
*Check Again*, or simply switching back to the IDE, shows the HEIC images without a restart.

- Whether the packages are preinstalled depends on the Windows image and the PC maker (the Windows 11 25H2 image of
  GitHub Actions has both, Windows Server 2025 has neither). In PowerShell,
  `Get-AppxPackage *HEIFImageExtension*; Get-AppxPackage *HEVCVideoExtension*` lists what is installed.
- Editions without the Microsoft Store (Windows Server, LTSC) cannot get the HEVC codec from the Store; on Windows
  Server 2025 winget installs the HEIF Image Extension, but not the HEVC codec.
- 64-bit Windows only (x64 and arm64), like the IDEs.
- `idea.log` tells what was found: `HEIC decoder: Windows Imaging Component with the HEIF Image Extension through JNA
  5.17.0 (amd64); CreateDecoder(HEIF): 0x00000000 (S_OK); test image: decoded (64x128); HEVC decoders: ...`, or the
  error codes of the missing component.

Store IDs, prices and minimum Windows versions from the Microsoft Store catalog (September 2026) and Microsoft's
[HEIF codec documentation](https://learn.microsoft.com/windows/win32/wic/heif-codec).

### Linux: libheif

On Linux, HEIC images are decoded by the system's libheif (`libheif.so.1`) with its HEVC decoder, libde265 (in newer
releases a separate plugin package). If either is missing, the banner above a HEIC image shows the command for the
distribution (detected from `/etc/os-release`), with *Copy Command*; after installing, *Check Again*, or simply
switching back to the IDE, shows the HEIC images without a restart.

| Distribution | Command |
|---|---|
| Ubuntu 23.10 and newer (24.04, 26.04, ...), Debian 13 and newer, Linux Mint 22, Pop!_OS 24.04, ... | `sudo apt install libheif1 libheif-plugin-libde265` |
| Ubuntu 20.04 / 22.04, Debian 11 / 12, Linux Mint 20 / 21, ... (libde265 is linked in) | `sudo apt install libheif1` |
| Fedora: Fedora's libheif has no HEVC decoder (patents); RPM Fusion Free adds it (`libheif-freeworld`) | `sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm && sudo dnf install libheif-freeworld` |
| RHEL, AlmaLinux, Rocky Linux, CentOS Stream: libheif from EPEL, the HEVC decoder from RPM Fusion Free | `sudo dnf install --nogpgcheck https://dl.fedoraproject.org/pub/epel/epel-release-latest-$(rpm -E %rhel).noarch.rpm https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-$(rpm -E %rhel).noarch.rpm && sudo /usr/bin/crb enable && sudo dnf install libheif-freeworld` |
| openSUSE Tumbleweed, Slowroll, Leap: openSUSE's libheif has no HEVC decoder; Packman Essentials has `libheif-HEIF` | `sudo zypper addrepo -cfp 90 https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/ packman-essentials && sudo zypper --gpg-auto-import-keys refresh packman-essentials && sudo zypper install --from packman-essentials libheif1 libheif-HEIF` (`openSUSE_Slowroll`, `openSUSE_Leap_15.6`, ... for the others) |
| Arch Linux, Manjaro, EndeavourOS, ... | `sudo pacman -S --needed libheif libde265` |
| Alpine | `sudo apk add libheif` |
| NixOS | `nix-env -iA nixos.libheif.lib`, or `pkgs.libheif.lib` in `environment.systemPackages` |

When libheif is installed but has no HEVC decoder, the plugin asks for the decoder only:
`sudo apt install libheif-plugin-libde265` on Debian and Ubuntu, `sudo apk add libheif-libde265` on Alpine 3.24 and newer;
the commands above for the other distributions.

- **Ubuntu 24.04 and newer**: since February 2026 (`libheif1` 1.17.6-1ubuntu4.3 in 24.04, and in 25.10 and 26.04)
  `libheif-plugin-libde265` is only suggested ([LP: #2142762](https://bugs.launchpad.net/ubuntu/+source/libheif/+bug/2142762)),
  so a `libheif1` that another package pulled in (ImageMagick, GIMP, ...) cannot decode HEIC photos by itself.
- **NixOS** has no global library path: the plugin also looks in `/run/current-system/sw/lib`, `~/.nix-profile/lib`
  and `/etc/profiles/per-user/<user>/lib`.
- **Flatpak** builds of an IDE only see the libraries of their Flatpak runtime. Use the IDE from the JetBrains Toolbox
  App, a tarball or a Snap (JetBrains' Snaps use classic confinement, so the system's libraries are found as usual), or
  set the path below to a libheif inside the sandbox.
- **Other locations** (a libheif you built, another prefix): *Settings | Advanced Settings | HEIC Viewer | libheif
  library* takes the path of `libheif.so.1` or of the directory that contains it.
- `idea.log` tells what was found: `HEIC decoder: libheif 1.17.6 (/usr/lib/x86_64-linux-gnu/libheif.so.1.17.6) with
  libde265 HEVC decoder ...`, or which libraries were tried, or the plugin directories searched for an HEVC decoder.

Package names checked in September 2026 against [packages.ubuntu.com](https://packages.ubuntu.com/search?keywords=libheif1),
[packages.debian.org](https://packages.debian.org/search?keywords=libheif-plugin-libde265),
[RPM Fusion](https://admin.rpmfusion.org/pkgdb/package/free/libheif-freeworld/) ([configuration](https://rpmfusion.org/Configuration),
[why](https://discussion.fedoraproject.org/t/libheif-vs-libheif-freeworld/147974)),
[packages.fedoraproject.org](https://packages.fedoraproject.org/pkgs/libheif/libheif/),
[openSUSE:Factory/libheif](https://build.opensuse.org/package/show/openSUSE:Factory/libheif) and
[Packman](https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/),
[archlinux.org](https://archlinux.org/packages/extra/x86_64/libheif/), [Alpine](https://pkgs.alpinelinux.org/packages?name=libheif*)
and [nixpkgs](https://github.com/NixOS/nixpkgs/blob/nixos-unstable/pkgs/by-name/li/libheif/package.nix); the
*Linux install commands* CI job runs them in containers of these distributions.

## Settings

*Settings | Advanced Settings | HEIC Viewer*:

| Setting | ID | Default |
|---|---|---|
| Maximum decoded image size (megapixels, 1–512). Larger images are downscaled while decoding; the longer side is also limited to 16384 pixels. The image viewer always asks for full resolution and the diff decodes two images at once, so this bounds memory use. | `heic.viewer.max.megapixels` | 64 |
| Show thumbnails as HEIC file icons. Applies as soon as the settings dialog is closed. | `heic.viewer.project.view.thumbnails` | on |
| Linux only: libheif library, the path of `libheif.so.1` or of its directory, for a libheif outside the system's library path (see [Linux: libheif](#linux-libheif)). Applies at the next IDE start or with *Check Again* in the banner or notification. | `heic.viewer.libheif.path` | empty: the system's libheif |

## Limitations and known issues

- Decoding relies on the operating system's decoder. Without it (Windows or Linux without the components listed under
  [Requirements](#requirements), or another OS) HEIC files open as images but show "Image not loaded"; a banner above
  the image (and, for the diff and the thumbnails, a notification once per session) tells you what to install.
  *Check Again*, or coming back to the IDE after opening the Store page or copying the install command, loads the open
  HEIC images without a restart; diffs that are already open have to be opened again.
- Colors are converted to **sRGB**; Display P3 colors outside sRGB are clipped.
- **HDR gain maps are ignored**: the standard dynamic range base image is shown.
- Only the **primary image** is shown (no other images of collections, frames of `.heics` sequences, depth maps, …).
- Images above the pixel budget are downscaled: the editor's info label shows the decoded size, while the
  documentation popup and completion show the original size.
- Truncated files (cut short, e.g. a partial copy or download) show "Image not loaded" (the reason is logged) instead
  of a black image. A file of full length whose compressed image data is damaged (bit flips, or a zero-filled tail
  left by an interrupted pre-allocated download) may still appear black, because the system decoder (at least
  ImageIO.framework on macOS) reports no error for it.
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
- **Windows colors** come from Microsoft's HEIF decoder. In CI (HEIF Image Extension 1.2.36) it converted several single
  (non-grid) test images that signal BT.601 YCbCr coefficients (written by libheif and macOS) with BT.709 coefficients,
  so saturated colors shift (pure red decodes as (255, 25, 0)); the grid images, the layout iPhone photos have, came out
  right, including one in Display P3. The plugin shows what Windows decodes, like Windows' own apps.
- **Windows**: the first HEIC image after the IDE starts can take a second or two while Windows activates the Store
  packages; the availability check pays this in the background during startup.
- **Linux**:
  - libheif decodes in software at full resolution and the plugin downscales afterwards: slower than macOS (a
    12-megapixel photo stored as 512-pixel tiles, like an iPhone's, takes about 0.3 s, a single 12-megapixel tile
    about 0.7 s, measured with libheif 1.23 on Apple M-series), and a large image needs 3-4 bytes per pixel of native
    memory while it is decoded. Embedded thumbnails make the file icons fast.
  - ICC profiles are converted to sRGB (Java's color management), but colors signaled only by an `nclx` profile with
    other primaries than sRGB/BT.709 (e.g. Display P3 or BT.2020 without an ICC profile) are shown unconverted, a
    little less saturated; HDR images (PQ/HLG transfer) look flat.
  - The orientation comes from the HEIF transformations (`irot`, `imir`, which libheif applies); an EXIF orientation
    without them (not written by cameras and phones, which store both) is ignored.
  - Old libheif versions decode less: the libheif 1.6 of Ubuntu 20.04 fails on images with transparency and does not
    read 10-bit files (brand `heix`); such files show "Image not loaded". The CI checks libheif 1.6, 1.12, 1.15, 1.16,
    1.17, 1.19, 1.21 and 1.23.
- Not verified in a real IDE yet: Windows (the decoder tests pass in CI on Windows 11 arm64, where HEIC is decoded, and
  on Windows Server 2025 x64 without the HEIF/HEVC extensions), Linux (the decoder tests pass in CI on Ubuntu 22.04 and
  24.04, x64 and arm64), Intel Macs (the decoder tests pass on them in CI), macOS versions before 26, HEIC files stored
  with Git LFS.

## How it works

1. **File type**: `META-INF/plugin.xml` declares `<fileType name="Image" extensions="heic;heif;hif;heics"/>` without an
   implementation class, which merges the extensions into the platform's existing "Image" file type (the same technique
   the bundled WebP support uses). The image editor and the binary diff viewer then handle HEIC files. Everything is
   declared in `plugin.xml` and loads on every OS; there is no OS module dependency (in IntelliJ 2024.1 – 2025.1 even an
   optional one makes the IDE refuse the plugin on the other systems).
2. **ImageIO reader**: the IDE's image editor, diff (`IfsUtil`) and image info index (`ImageInfoReader`) choose a
   `javax.imageio` reader by content. `HeicImageReaderSpi.canDecodeInput` runs only the pure-Java `HeifSniffer`
   (reads at most 512 bytes of the `ftyp` box), never loads native code and never throws, so other formats are never
   affected. AVIF and MP4/MOV brands are rejected. The format name is `heic`, so the info label says `HEIC`.
3. **Decoding backends** (`backend` package): `HeifBackend` is the platform-neutral decoder interface (`readInfo`,
   `decode(maxPixelSize)`, `decodeThumbnail(maxPixelSize)` and a cached availability probe, `status()`, that returns
   `HeifBackendStatus`: available, or unavailable with a machine-readable reason such as
   `WINDOWS_HEIF_EXTENSION_MISSING` or `LINUX_LIBHEIF_MISSING`, a detail text and optionally an install page and
   command). `HeifBackends` picks the backend for the running OS:

   | OS | Backend | System decoder |
   |---|---|---|
   | macOS | `mac.MacHeifBackend` | ImageIO.framework |
   | Windows | `win.WicHeifBackend` | Windows Imaging Component with the HEIF Image Extension and the HEVC Video Extensions (Microsoft Store) |
   | Linux | `linux.LibheifHeifBackend` | libheif 1.x (`libheif.so.1`) with an HEVC decoder (libde265) |

   `AbstractHeifBackend` implements what is the same everywhere: before any native call the data must pass
   `HeifInput` (the `HeifSniffer` check, because system decoders pick the codec by content, and the pure-Java
   `IsoBoxes` check that the top-level boxes and the `iloc` data are complete, because ImageIO.framework
   "successfully" decodes truncated files as black images); an unavailable backend fails with an `IOException`; native
   failures (including `LinkageError`s) become `IOException`s. `PixelPipeline` turns native pixel buffers into
   `BufferedImage`s in strips (8-bit sRGB, `TYPE_INT_RGB`, or non-premultiplied `TYPE_INT_ARGB` with alpha) and
   provides un-premultiplying, the EXIF/HEIF orientation, downscaling and ICC-to-sRGB conversion for backends whose
   decoder cannot do it. Native code is reached only through the JNA that ships with the IDE (`backend.jna.JnaLibraries`):
   only its untyped layer (`NativeLibrary`, `Function.invoke*` with JDK-typed arguments, `Native.malloc`/`free`), never
   `Library` interfaces, `Structure`s, callbacks or `Memory`, whose JNA caches would keep the plugin class loader alive,
   and libraries are opened so that JNA's Cleaner thread cannot inherit the plugin's context.
4. **macOS decoder** (`mac` package): `HeicDecoder` creates a `CGImageSource` (no caching), reads the primary image's
   properties, creates a transformed (orientation-applied) image with `CGImageSourceCreateThumbnailAtIndex`, draws it in
   bands of about one megapixel (cropped with `CGImageCreateWithImageInRect`) into an explicit 8-bit sRGB bitmap context
   and copies the pixels into a `BufferedImage`. Every call has its own autorelease pool and all CF objects and native
   buffers are released in `finally` blocks. The image source must report a HEIF-family type (`public.heic`,
   `public.heif`, …). `MacApi` is the list of native calls it needs and `jna.JnaMacApi` implements it; a `CGRect` is
   passed by value as four doubles on arm64 and as eight dummy doubles (filling `xmm0`–`xmm7`) followed by the four
   components on the stack on x86_64.
5. **Windows decoder** (`win` package): `WicDecoder` initializes COM on the calling thread (`CoInitializeEx`,
   multithreaded, balanced by `CoUninitialize`; a thread that already is in a single-threaded apartment, such as an AWT
   thread, is used as it is), creates the WIC imaging factory, a memory stream (`SHCreateMemStream`) and a decoder from
   it (`CreateDecoderFromStream`; the decoder must report `GUID_ContainerFormatHeif`), and takes frame 0, the primary
   image. Microsoft's HEIF decoder applies the HEIF `irot`/`imir` transformations itself and reports
   `System.Photo.Orientation` = 1 (the EXIF orientation is ignored, as the HEIF standard requires; the reported
   orientation is applied anyway). Its frames are 32-bit BGR: the alpha of an image comes as a separate 8-bit plane
   through `IWICBitmapSourceTransform`. The frame goes through `IWICBitmapScaler` (Fant, only when the image is larger
   than requested), `IWICFormatConverter` and `CreateBitmapFromSource` (decoded once) and is copied into the
   `BufferedImage` in strips; an embedded ICC profile (e.g. Display P3) is converted to sRGB with `PixelPipeline`. Every
   COM object and buffer is released on every path. `WinApi` is the list of native calls and `jna.JnaWinApi` implements
   them as COM vtable calls through JNA's `Function`, with the vtable slots of the Windows SDK's `wincodec.idl` (checked
   against the mingw-w64 headers); `MFTEnumEx` takes a GUID by value, passed as a pointer to a copy on x64 and in two
   registers on arm64. `WicProbe` decides the status by decoding a 428-byte embedded HEIC and asking Media Foundation
   for HEVC decoders: no HEIF decoder (`WINCODEC_ERR_COMPONENTINITIALIZEFAILURE`) is `WINDOWS_HEIF_EXTENSION_MISSING`,
   no HEVC codec (`MF_E_TOPO_CODEC_NOT_FOUND`, no HEVC decoder) is `WINDOWS_HEVC_EXTENSION_MISSING`, each with its
   Microsoft Store page (`WindowsCodecs`).
6. **Linux decoder** (`linux` package): `Libheif` binds the libheif C API (only functions that exist since libheif
   1.6; newer ones such as `heif_init` are used when present). The probe loads `libheif.so.1` (then `heif`, the NixOS
   profiles, or the configured path), calls `heif_init` once (it loads the codec plugins; `heif_deinit` is never called,
   because libheif's initialization is process-wide and the plugins must stay loaded for other users and for decodes
   still running while the plugin is unloaded) and asks `heif_have_decoder_for_format(HEVC)`; without a decoder it
   rescans the plugin directories (so that *Check Again* finds a plugin installed meanwhile), then reports
   `LINUX_HEVC_PLUGIN_MISSING`. `LinuxDistribution` reads `/etc/os-release` and `LibheifRemedy` turns it into the install
   command. `LibheifDecoder` copies the data to native memory (`heif_context_read_from_memory_without_copy` keeps
   pointing into it until the context is freed), takes the primary image handle (its size is the displayed size:
   libheif applies `irot`/`imir`/`clap` while decoding), decodes it with `heif_decode_image` to interleaved 8-bit RGB
   or RGBA and reads the plane in strips; `PlaneConverter` downscales while reading (an alpha-weighted box filter by
   the largest integer factor, then one bilinear step), so the Java heap never holds more than the requested size. An
   ICC profile is converted to sRGB with `PixelPipeline`. `decodeThumbnail` uses an embedded thumbnail with the same
   aspect ratio when it is at least as large as requested. libheif returns `struct heif_error` (16 bytes) by value:
   on x86-64 (System V) and AArch64 such a struct comes back in two registers (`RAX`/`RDX`, `X0`/`X1`), so the
   functions are called with `Function.invokeLong` and the first register holds `code` (low 32 bits) and `subcode`
   (high 32 bits); other architectures are refused. Every context, handle, image and native buffer is released in
   `finally` blocks.
7. **Registration**: `AppLifecycleListener.appFrameCreated` (normal start, before projects and editor tabs are
   restored), `DynamicPluginListener.pluginLoaded` / `beforePluginUnload` (installation, update and removal without
   restart), and `HeicReaderRegistrar`, a `fileEditorProvider` for the Image file type whose `accept` registers the
   reader and always returns `false`: the command-line diff and merge (e.g. `studio diff a.heic b.heic` while the IDE is
   not running) never create an IDE frame, but ask the editor providers before they create their image viewers. It
   never creates an editor, so loading or unloading the plugin leaves open editors and diff windows alone. After
   `beforePluginUnload` nothing registers the reader again. Stale copies left by an old plugin class loader are
   removed, and `IIORegistry.setOrdering` makes this reader win over other plugins' HEIF readers. Deliberately not
   `ApplicationLoadListener` (internal API, not dynamic), not the 262-only `imageReaderWriterSpi` extension point
   (unknown before 262, which would block dynamic unloading), and no `META-INF/services` entry.
   After registering, `HeicSupport` checks that `ImageIO` itself finds the reader. `IIORegistry.getDefaultInstance()`
   is not thread-safe, and if two threads call it for the first time at once while the IDE starts, `ImageIO` can keep a
   different registry for the whole session (the most likely cause of a one-off start on an Android Studio 2026.2
   canary in which no HEIC image loaded). In that case a second instance is
   added to `ImageIO`'s registry through `ImageIO.scanForPlugins()`, with a context class loader that names only this
   reader. It is removed again on unload, and the split is logged as a warning in `idea.log`.
8. **Missing decoder** (`ui` package): after registering, the backend's status is probed on a pooled thread; the UI
   only ever reads the backend's cached status and never probes on the EDT (`DecoderStatus`). When the decoder is
   unavailable, the user is told where a HEIC image fails to load, with the remedy of the reason
   (`backend.HeifRemedies`): on Windows *Open Microsoft Store* (the Store app page of the missing package, from
   `win.WindowsCodecs`), *Check Again* and, under *More*, the winget command, the Store's web page and *Learn More*; on
   Linux the package command of the detected distribution (`linux.LibheifRemedy`, shown in the banner) with
   *Copy Command*, *Check Again* and *Learn More* (or, without a command for the system, the README's instructions);
   *Check Again* and *Report a Problem* for an unexpected error, *Learn More* on an unsupported system:
   - a **banner** above HEIC image editors (`HeicDecoderNotificationProvider`, `editorNotificationProvider`). The
     platform collects banners by itself only for text editors, so `HeicFileOpenedListener` asks for it when a HEIC
     file is opened while the decoder is missing;
   - a **notification** (group "HEIC Viewer", balloon) at most once per session where there is no banner: a diff of
     HEIC files (`HeicDiffExtension`, `diff.DiffExtension`), a thumbnail of a file that is not open, or right after the
     plugin was installed; *Don't Show Again* per reason.

   *Check Again* probes again on a pooled thread. Once the decoder is found, the banners disappear, the open HEIC
   editors load their image (`ImageEditorImpl.refreshFile()`, as after a change on disk), the thumbnails are decoded
   again and a notification confirms it; if it is still missing, a notification says what is missing now. After the
   user opened the Store page or copied the install command, the next activation of the IDE window checks again by
   itself (`HeicActivationListener`, debounced, for 30 minutes). A normal start with a working decoder shows nothing and
   posts nothing to the EDT. Before the plugin is unloaded, its banners are removed explicitly (IntelliJ 2026.1 leaves the
   panels of an unloaded provider in the editor, which would keep the class loader alive) and its notifications expire.
   `-Dheic.viewer.debug.backendStatus=<REASON>[|<url>[|<command>]]` forces a status, to see the UI of another OS; with
   `-Dheic.viewer.debug.backendStatus.recover=true` the first *Check Again* switches to the real decoder of the OS.
9. **Thumbnails** (`thumbnail` package): `HeicThumbnailIconProvider` (`fileIconProvider`, `order="first"`) never
   decodes in `getIcon`: it looks up an LRU cache (500 entries, keyed by URL, VFS timestamp, length, icon size and the
   maximum screen scale) and otherwise queues a decode on the plugin's own bounded executor (two threads) and returns
   `null`, so the platform shows the default Image icon. The background task reads the file directly from disk (only
   if its header is a HEIC/HEIF `ftyp` box: a file merely named `.heic` never reaches a system decoder), calls the
   backend's `decodeThumbnail` (which may use the file's embedded thumbnail) and renders a HiDPI-aware square icon
   with Java2D (progressive bilinear downscaling, centered, a faint one-pixel outline). When the icon is ready,
   `VirtualFileAppearanceListener.fireVirtualFileAppearanceChanged` refreshes the Project view, tabs and navigation
   bar. Only platform and JDK objects are handed to the platform, and `beforePluginUnload` shuts the executor down,
   waits up to one second for running decodes and clears the caches, so the plugin class loader can be unloaded.
10. **Java 17 and dynamic unloading**: the plugin is compiled with `--release 17`. On JBR 17 (IntelliJ 2024.1) two
   things would keep the plugin class loader alive after an unload, so neither is used: records (their
   `equals`/`hashCode`/`toString` bootstraps are cached by the JDK; value classes such as `DecodeLimits` are
   hand-written instead), and EDT events posted by plugin code during a normal start (`HeicFileTypeMappingRepair` only
   checks the file type mappings synchronously and posts to the EDT when a repair is actually needed).
   `BytecodeLevelTest` checks the packaged jar (class version, no records, no `java.lang.foreign`, the JNA rules) and
   `PluginClassLoaderLeakTest` checks on JDK 17, 21 and 25 that the class loader is collected after the plugin decoded
   images and was shut down.

## Development

### Requirements

- Builds and tests on macOS, Windows and Linux. The decoder tests run where the system decoder is available (macOS;
  Windows and Linux with the components listed under [Requirements](#requirements)); the rest runs everywhere. The
  tests of the Linux backend (`linux` package) also run on macOS against Homebrew's libheif (`brew install libheif`),
  or against any libheif given as `HEIC_TEST_LIBHEIF=/path/to/libheif`, and compare its images with ImageIO.framework's.
- JDK 25 as the Gradle toolchain (the code is compiled with `--release 17`), plus JDK 21 and JDK 17 for `testJdk21` /
  `testJdk17`. Gradle picks up installed JDKs, including the one it runs on; otherwise the foojay resolver downloads
  them (see below to reuse an IDE's JBR 25).
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
./gradlew test             # JUnit 5 tests on JDK 25 (decoder tests need a system decoder, e.g. macOS)
./gradlew testJdk21        # the same tests on JDK 21 (runtime of IDEs 2024.2 - 2026.1.2)
./gradlew testJdk17        # the same tests on JDK 17 (IntelliJ 2024.1), tests tagged "platform" excluded
./gradlew check            # test, testJdk21 and testJdk17
./gradlew testJdk25        # `test` as a plain Test task (CI: it also runs where the compile IDE has no build for the
                           # CPU architecture, e.g. Linux/Windows arm64)
./gradlew verifyPlugin     # Plugin Verifier: local IDEs, or pluginVerificationIdes from gradle.properties
./gradlew runIde           # sandboxed IDE with the plugin
./gradlew runIdeCanary     # sandboxed second IDE (needs platformCanaryPath)
./gradlew clean buildPlugin test testJdk21 testJdk17 verifyPlugin   # everything CI checks
```

The run configurations in `.run/` (Run Plugin, Run Tests, Run Verifications) wrap the same tasks.

Expected warnings: `verifyPluginProjectConfiguration` notes that since-build 241 is lower than the platform compiled
against (261) and that Java 17 is lower than the Java 21 that 261 requires. Both are intended: the plugin supports
2024.1 (JBR 17), and Plugin Verifier checks the API against the oldest IDEs in `pluginVerificationIdes`.

The platform jars of the IDE compiled against are Java 21 bytecode, so `testJdk17` runs with a minimal class path (the
plugin jar, JUnit and the IDE's `util-8.jar`, which contains JNA). Tests that need other IDE classes are tagged
`platform` and run only on JDK 21 and 25. The `*PlatformIntegrationTest` classes start a light IDE (IntelliJ test
framework, `BasePlatformTestCase`) and therefore run only in `test`: `HeicPlatformIntegrationTest` (the HEIC extensions
are Image files once plugin.xml is loaded, and the IDE's `IfsUtil` decodes a HEIC file through the reader) and
`DecoderUiPlatformIntegrationTest` (banner, notifications, *Check Again* and the check on activation, with a fake
backend).

### Project structure

```
.github/                      GitHub Actions workflows (build, cross-platform, release), Dependabot, issue templates
.run/                         Shared IDE run configurations
gradle/libs.versions.toml     Version catalog (IntelliJ Platform Gradle Plugin, Changelog plugin, JUnit)
src/main/java/cn/yooss/heic/
  HeifSniffer                 Pure-Java ftyp sniffing (never touches native code)
  HeicImageReaderSpi          javax.imageio service provider (format names, suffixes, MIME types, canDecodeInput)
  HeicImageReader             Reader: input, size, image types, subsampling/source region/pixel budget
  DecodeLimits                Pixel budget
  HeicSupport                 Registration in IIORegistry (ordering, stale copies, split registry)
  HeicSettings, HeicBundle    Advanced Settings access, resource bundle
  HeicFileTypeMappingRepair   IJPL-39443 workaround
  HeicAppLifecycleListener, HeicDynamicPluginListener   Registration on start / dynamic load and unload
  HeicReaderRegistrar         Registration in the command-line diff and merge (an editor provider that never accepts)
  backend/                    HeifBackend, HeifBackendStatus, HeifBackends (selection by OS), AbstractHeifBackend,
                              HeifImageInfo, HeifInput + IsoBoxes (input checks), PixelPipeline,
                              UnavailableHeifBackend, HeifRemedy + HeifRemedies (what the user can do about each
                              unavailable status); jna/JnaLibraries (JNA rules, library loading)
  mac/                        MacHeifBackend, HeicDecoder (the ImageIO.framework algorithm), MacApi, jna/JnaMacApi
  win/                        WicHeifBackend, WicDecoder (the WIC algorithm), WicProbe (availability), WinApi,
                              jna/JnaWinApi (COM vtable calls), Guids, Hresult, WindowsCodecs (Store product ids)
  linux/                      LibheifHeifBackend (probe), Libheif (the libheif C API through JNA), LibheifDecoder,
                              PlaneConverter (streaming downscaling), LinuxDistribution + LibheifRemedy (os-release,
                              install commands)
  thumbnail/                  Thumbnail icons: provider, loader, cache, renderer, listeners
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
  `linux.LibheifHeifBackend`; `HeifBackends` selects the backend by OS. The Windows backend shows the pattern: the
  native calls behind an interface (`WinApi`), a fake of it that checks releases on every path on every OS
  (`FakeWinApi`), and the probe's decision table as plain Java (`WicProbe`).
- `probe()`: load the system libraries through `backend.jna.JnaLibraries` and return
  `HeifBackendStatus.available(...)` or `unavailable(Reason, detail)` with `withInstallUrl` (e.g. a Microsoft Store
  link) or `withInstallCommand` (the distribution's package command).
- What the user sees for a reason (banner, notifications) is its remedy in `backend.HeifRemedies`: the texts
  `remedy.title.<REASON>` and `backend.status.<REASON>` in both message bundles, and the actions (install page or
  command, *Check Again*, *Learn More*). The URL and command a backend passes replace the defaults of `HeifRemedies`
  (only `https:` and `ms-windows-store:` URLs and single-line commands of at most 500 characters are accepted;
  `HeifBackendContractTest` checks that the backend of the OS passes acceptable ones). `HeifRemediesTest` checks every
  remedy.
- `doReadInfo` / `doDecode` / `doDecodeThumbnail`: the input checks, the availability check and the wrapping of native
  failures are done by the base class. Produce the images with `PixelPipeline` (8-bit sRGB, straight alpha,
  orientation applied, never larger than `maxPixelSize`).
- Follow the JNA rules in `JnaLibraries` (no `Library`/`Structure`/`Memory`/callbacks, strings as `utf8z`/`utf16z`
  arrays); `BytecodeLevelTest` and `PluginClassLoaderLeakTest` check them.
- `HeifBackendContractTest` must pass on the OS; in `.github/workflows/cross-platform.yml`, set `expect` of each job to
  the status its runner must report (`available`, or e.g. `LINUX_LIBHEIF_MISSING`).
- `-Dheic.viewer.debug.backendStatus=<REASON>[|<url>[|<command>]]` shows the banner and notifications of any status on
  any OS (with `-Dheic.viewer.debug.backendStatus.recover=true`, *Check Again* then finds the real decoder).

### Plugin description and change notes

- The description shown on JetBrains Marketplace and in the IDE's plugin manager is the Markdown between
  `<!-- Plugin description -->` and `<!-- Plugin description end -->` at the top of this file; the build converts it to
  HTML and writes it into `plugin.xml`. It must stay in English.
- The change notes come from `CHANGELOG.md` ([Keep a Changelog](https://keepachangelog.com) format): the section of
  the current version, or `## [Unreleased]` if there is none. New entries go under `## [Unreleased]`.

### Continuous integration and releases

- `.github/workflows/build.yml` runs on every push to `main` and on pull requests: build and tests (`check`: JDK 25, 21
  and 17) on `macos-latest`, Plugin Verifier on `ubuntu-latest` against `pluginVerificationIdes`, and a draft GitHub
  release (from the `[Unreleased]` section of `CHANGELOG.md`) for pushes to `main`.
- `.github/workflows/cross-platform.yml` runs on pushes to `dev/**` branches and manually: build and the tests on JDK 25,
  21 and 17 on macOS arm64 and x86_64, Linux x64 (libheif 1.17 with and without its HEVC plugin, libheif 1.12 on
  Ubuntu 22.04, no libheif), Linux arm64 (libheif 1.17) and Windows x64 and arm64 (the IDE to compile against is
  downloaded for each OS and cached; on arm64 Linux and Windows, which Android Studio has no build for, IntelliJ IDEA),
  the light-IDE tests (`test`, on the jobs whose IDE has a build for the runner), and Plugin Verifier. Each job sets
  `HEIC_EXPECT_BACKEND` to the decoder status `HeifBackendContractTest` must see on that runner (`available`, or a
  `HeifBackendStatus.Reason` such as `LINUX_LIBHEIF_MISSING`). Test reports are uploaded as artifacts. The *Linux
  install commands* job runs the install command the plugin suggests in containers of Ubuntu, Debian, Fedora,
  AlmaLinux, openSUSE, Arch Linux, Alpine and Nix, and checks with the plugin's classes that libheif is then found
  and decodes.
  Windows: the Windows 11 arm64 image has the
  HEIF and HEVC extensions (HEIC is decoded, expected `available`); Windows Server 2025 x64 runs once as it is
  (`WINDOWS_HEIF_EXTENSION_MISSING`) and once with the HEIF Image Extension installed by winget (the HEVC codec cannot
  be installed there: `WINDOWS_HEVC_EXTENSION_MISSING`). The IntelliJ IDEA downloaded on Windows arm64 is the x64 build,
  so JNA's arm64 library comes from the JNA release of the same version: `-PjnaNativeDir=<directory with
  jnidispatch.dll>` is used when the IDE has no `lib/jna/<arch>` directory for the test JVM.
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

- 0.2: IntelliJ 2024.1+ / Android Studio Koala+ (JNA instead of FFM, Java 17), and Windows (WIC) and Linux (libheif)
  support through the systems' own decoders, behind the `HeifBackend` interface.

## License

[MIT](LICENSE) © 2026 ZhuJHua
