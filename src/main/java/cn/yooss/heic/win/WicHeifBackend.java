package cn.yooss.heic.win;

import cn.yooss.heic.backend.AbstractHeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.PixelPipeline;
import cn.yooss.heic.backend.jna.JnaLibraries;
import cn.yooss.heic.win.jna.JnaWinApi;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Windows backend: the Windows Imaging Component (WIC) with the Microsoft Store "HEIF Image Extension" (the HEIF
 * container decoder) and "HEVC Video Extensions" (the HEVC codec, a Media Foundation transform), reached through the
 * IDE's JNA. See {@link WicDecoder} for the algorithm and {@link WicProbe} for the availability check; the plugin
 * bundles no codec.
 * <p>
 * {@link #probe()} reports {@link HeifBackendStatus.Reason#WINDOWS_HEIF_EXTENSION_MISSING} or
 * {@link HeifBackendStatus.Reason#WINDOWS_HEVC_EXTENSION_MISSING} with the Microsoft Store page as install URL (see
 * {@link WindowsCodecs}); the plugin then prompts the user to install it. Only 64-bit Windows on x64 and arm64 is
 * supported (every IDE of the supported versions is 64-bit).
 */
public final class WicHeifBackend extends AbstractHeifBackend {
  private final Object lock = new Object();
  private final Supplier<WicDecoder> decoderFactory;
  private final String osName;
  private final String osArch;
  private volatile WicDecoder decoder;

  public WicHeifBackend() {
    this(() -> new WicDecoder(new JnaWinApi()), System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
  }

  /** Tests: a decoder on another {@link WinApi}, as if running on {@code osName} and {@code osArch}. */
  WicHeifBackend(@NotNull Supplier<WicDecoder> decoderFactory, @NotNull String osName, @NotNull String osArch) {
    this.decoderFactory = decoderFactory;
    this.osName = osName;
    this.osArch = osArch;
  }

  @Override
  public @NotNull String id() {
    return "windows-wic";
  }

  @Override
  public @NotNull String displayName() {
    return "Windows Imaging Component (HEIF Image Extension)";
  }

  @Override
  protected @NotNull HeifBackendStatus probe() {
    // Nothing native is loaded before these checks.
    if (!osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.UNSUPPORTED_OS, "Not Windows: " + osName);
    }
    if (!isSupportedArchitecture(osArch)) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.UNSUPPORTED_OS,
                                           "Unsupported processor architecture " + osArch + " (64-bit x64 or arm64 only)");
    }
    if (!JnaLibraries.isPresent()) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "The IDE does not provide JNA (com.sun.jna)");
    }
    return WicProbe.run(decoder()).toStatus();
  }

  static boolean isSupportedArchitecture(String arch) {
    String a = arch.toLowerCase(Locale.ROOT);
    return a.equals("amd64") || a.equals("x86_64") || a.equals("aarch64") || a.equals("arm64");
  }

  /** The decoder with its native binding, created (and the system libraries loaded) on first use. */
  WicDecoder decoder() {
    WicDecoder result = decoder;
    if (result != null) return result;
    synchronized (lock) {
      if (decoder == null) decoder = decoderFactory.get();
      return decoder;
    }
  }

  @Override
  protected @NotNull HeifImageInfo doReadInfo(byte[] data) throws IOException {
    return decoder().readInfo(data, true);
  }

  @Override
  protected @NotNull BufferedImage doDecode(byte[] data, int maxPixelSize) throws IOException {
    return decoder().decode(data, maxPixelSize, true, PixelPipeline.STRIP_PIXELS);
  }

  /** See {@link WicDecoder#decodeHeapBytes}. */
  @Override
  public long decodeHeapBytes(@NotNull HeifImageInfo info, int maxPixelSize) {
    return WicDecoder.decodeHeapBytes(info, maxPixelSize);
  }
}
