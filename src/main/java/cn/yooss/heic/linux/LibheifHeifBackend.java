package cn.yooss.heic.linux;

import cn.yooss.heic.backend.AbstractHeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifImageInfo;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Linux backend: the system libheif ({@code libheif.so.1}, loaded with {@code dlopen} through the IDE's JNA) with an
 * HEVC decoder plugin such as libde265. Nothing is bundled.
 * <p>
 * <b>Placeholder:</b> not implemented yet; {@link #probe()} reports
 * {@link HeifBackendStatus.Reason#NOT_IMPLEMENTED NOT_IMPLEMENTED}. The implementation reports
 * {@link HeifBackendStatus.Reason#LINUX_LIBHEIF_MISSING LINUX_LIBHEIF_MISSING} or
 * {@link HeifBackendStatus.Reason#LINUX_HEVC_PLUGIN_MISSING LINUX_HEVC_PLUGIN_MISSING} (with the distribution's
 * {@link HeifBackendStatus#withInstallCommand install command}) when a component is missing; the plugin then prompts
 * the user to install it.
 */
public final class LibheifHeifBackend extends AbstractHeifBackend {
  @Override
  public @NotNull String id() {
    return "linux-libheif";
  }

  @Override
  public @NotNull String displayName() {
    return "libheif";
  }

  @Override
  protected @NotNull HeifBackendStatus probe() {
    return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.NOT_IMPLEMENTED,
                                         "The Linux (libheif) HEIF backend is not implemented yet");
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
