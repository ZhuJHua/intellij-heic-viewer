package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * A backend that can never decode, with a fixed status: used for operating systems without a supported system
 * decoder ({@link HeifBackendStatus.Reason#UNSUPPORTED_OS}). Touches no native code.
 */
public final class UnavailableHeifBackend implements HeifBackend {
  private final String id;
  private final String displayName;
  private final HeifBackendStatus status;

  public UnavailableHeifBackend(@NotNull String id, @NotNull String displayName, @NotNull HeifBackendStatus status) {
    if (status.isAvailable()) throw new IllegalArgumentException("status must be unavailable: " + status);
    this.id = id;
    this.displayName = displayName;
    this.status = status;
  }

  /** The backend for an operating system without a supported system HEIF decoder. */
  public static @NotNull UnavailableHeifBackend unsupportedOs(@NotNull String osName, @NotNull String arch) {
    return new UnavailableHeifBackend("unsupported", "no system HEIF decoder", HeifBackendStatus.unavailable(
      HeifBackendStatus.Reason.UNSUPPORTED_OS, "No supported system HEIF decoder on " + osName + " (" + arch + ")"));
  }

  @Override
  public @NotNull String id() {
    return id;
  }

  @Override
  public @NotNull String displayName() {
    return displayName;
  }

  @Override
  public @NotNull HeifBackendStatus status() {
    return status;
  }

  /** The fixed status: nothing to probe. */
  @Override
  public @NotNull HeifBackendStatus cachedStatus() {
    return status;
  }

  @Override
  public @NotNull HeifBackendStatus recheckStatus() {
    return status;
  }

  @Override
  public @NotNull HeifImageInfo readInfo(byte[] data) throws IOException {
    throw unavailable();
  }

  @Override
  public @NotNull BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
    throw unavailable();
  }

  @Override
  public @NotNull BufferedImage decodeThumbnail(byte[] data, int maxPixelSize) throws IOException {
    throw unavailable();
  }

  private IOException unavailable() {
    return new IOException(displayName + " cannot decode HEIF images: " + status);
  }

  @Override
  public String toString() {
    return displayName;
  }
}
