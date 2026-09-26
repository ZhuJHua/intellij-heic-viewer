package cn.yooss.heic.mac;

import cn.yooss.heic.backend.AbstractHeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.jna.JnaLibraries;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;

/**
 * macOS backend: the HEIF decoder of ImageIO.framework (part of every supported macOS), through the IDE's JNA. See
 * {@link HeicDecoder} for the algorithm.
 * <p>
 * The probe only checks the OS, the processor architecture (the {@code CGRect} calling convention is known for arm64
 * and x86_64) and that JNA is present, without loading any native code: the frameworks are opened on the first decode.
 */
public final class MacHeifBackend extends AbstractHeifBackend {
  @Override
  public @NotNull String id() {
    return "macos-imageio";
  }

  @Override
  public @NotNull String displayName() {
    return "macOS ImageIO.framework";
  }

  @Override
  protected @NotNull HeifBackendStatus probe() {
    String os = System.getProperty("os.name", "");
    String arch = System.getProperty("os.arch", "");
    if (!os.toLowerCase(Locale.ROOT).startsWith("mac")) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.UNSUPPORTED_OS, "Not macOS: " + os);
    }
    if (!isSupportedArchitecture(arch)) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.UNSUPPORTED_OS, "Unsupported processor architecture " + arch);
    }
    if (!JnaLibraries.isPresent()) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "The IDE does not provide JNA (com.sun.jna)");
    }
    return HeifBackendStatus.available(displayName() + " through the IDE's JNA (" + os + " " + System.getProperty("os.version", "")
                                       + ", " + arch + ")");
  }

  static boolean isSupportedArchitecture(String arch) {
    return arch.equals("aarch64") || arch.equals("arm64") || arch.equals("x86_64") || arch.equals("amd64");
  }

  @Override
  protected @NotNull HeifImageInfo doReadInfo(byte[] data) throws IOException {
    return HeicDecoder.readInfo(data);
  }

  @Override
  protected @NotNull BufferedImage doDecode(byte[] data, int maxPixelSize) throws IOException {
    return HeicDecoder.decode(data, maxPixelSize);
  }
}
