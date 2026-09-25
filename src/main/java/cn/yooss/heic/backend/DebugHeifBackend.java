package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * The backend {@link HeifBackends#DEBUG_STATUS_PROPERTY} asks for: it reports a forced unavailable status, e.g. to see
 * the install prompt of another OS. With {@link HeifBackends#DEBUG_RECOVER_PROPERTY} the first
 * {@link #recheckStatus()} ("Check Again", or the check when the IDE is activated after a remedy action) switches to
 * the real backend of the running OS, as if the missing component had just been installed: on macOS this shows the
 * whole way from the prompt to the loaded image. Touches no native code until it has switched.
 */
final class DebugHeifBackend implements HeifBackend {
  private final HeifBackendStatus forced;
  private final boolean recoverOnRecheck;
  private final Supplier<HeifBackend> realBackend;
  private final Object lock = new Object();
  private volatile HeifBackend recovered;

  DebugHeifBackend(@NotNull HeifBackendStatus forced, boolean recoverOnRecheck, @NotNull Supplier<HeifBackend> realBackend) {
    if (forced.isAvailable()) throw new IllegalArgumentException("status must be unavailable: " + forced);
    this.forced = forced;
    this.recoverOnRecheck = recoverOnRecheck;
    this.realBackend = realBackend;
  }

  @Override
  public @NotNull String id() {
    return "debug";
  }

  @Override
  public @NotNull String displayName() {
    HeifBackend real = recovered;
    return real != null ? real.displayName() : "system HEIF decoder (" + HeifBackends.DEBUG_STATUS_PROPERTY + ")";
  }

  @Override
  public @NotNull HeifBackendStatus status() {
    HeifBackend real = recovered;
    return real != null ? real.status() : forced;
  }

  @Override
  public @Nullable HeifBackendStatus cachedStatus() {
    HeifBackend real = recovered;
    return real != null ? real.cachedStatus() : forced;
  }

  @Override
  public @NotNull HeifBackendStatus recheckStatus() {
    if (!recoverOnRecheck) return forced;
    HeifBackend real;
    synchronized (lock) {
      if (recovered == null) recovered = realBackend.get();
      real = recovered;
    }
    return real.recheckStatus();
  }

  @Override
  public @NotNull HeifImageInfo readInfo(byte[] data) throws IOException {
    return real().readInfo(data);
  }

  @Override
  public @NotNull BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
    return real().decode(data, maxPixelSize);
  }

  @Override
  public @NotNull BufferedImage decodeThumbnail(byte[] data, int maxPixelSize) throws IOException {
    return real().decodeThumbnail(data, maxPixelSize);
  }

  private HeifBackend real() throws IOException {
    HeifBackend real = recovered;
    if (real == null) throw new IOException(displayName() + " cannot decode HEIF images: " + forced);
    return real;
  }

  @Override
  public void dispose() {
    HeifBackend real = recovered;
    if (real != null) real.dispose();
  }

  @Override
  public String toString() {
    return displayName();
  }
}
