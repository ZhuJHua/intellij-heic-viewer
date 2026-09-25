package cn.yooss.heic.win;

import cn.yooss.heic.backend.AbstractHeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifImageInfo;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Windows backend: the Windows Imaging Component (WIC) with the Microsoft Store "HEIF Image Extensions" (the HEIF
 * container decoder) and "HEVC Video Extensions" (the HEVC codec), reached through the IDE's JNA.
 * <p>
 * <b>Placeholder:</b> not implemented yet; {@link #probe()} reports
 * {@link HeifBackendStatus.Reason#NOT_IMPLEMENTED NOT_IMPLEMENTED}. The implementation reports
 * {@link HeifBackendStatus.Reason#WINDOWS_HEIF_EXTENSION_MISSING WINDOWS_HEIF_EXTENSION_MISSING} or
 * {@link HeifBackendStatus.Reason#WINDOWS_HEVC_EXTENSION_MISSING WINDOWS_HEVC_EXTENSION_MISSING} (with the store link
 * as {@link HeifBackendStatus#withInstallUrl install URL}) when a component is missing; the plugin then prompts the
 * user to install it.
 */
public final class WicHeifBackend extends AbstractHeifBackend {
  @Override
  public @NotNull String id() {
    return "windows-wic";
  }

  @Override
  public @NotNull String displayName() {
    return "Windows Imaging Component (HEIF Image Extensions)";
  }

  @Override
  protected @NotNull HeifBackendStatus probe() {
    return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.NOT_IMPLEMENTED,
                                         "The Windows (WIC) HEIF backend is not implemented yet");
  }

  @Override
  protected @NotNull HeifImageInfo doReadInfo(byte[] data) throws IOException {
    throw new IOException("Not implemented");
  }

  @Override
  protected @NotNull BufferedImage doDecode(byte[] data, int maxPixelSize) throws IOException {
    throw new IOException("Not implemented");
  }
}
