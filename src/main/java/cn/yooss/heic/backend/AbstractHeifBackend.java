package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Base class of the real backends. It implements the parts of the {@link HeifBackend} contract that are the same on
 * every OS, so that a backend only has to talk to its system decoder:
 * <ul>
 *   <li>{@link #status()} runs {@link #probe()} once and caches the result ({@link #recheckStatus()} probes again); an
 *   exception thrown by the probe becomes an {@link HeifBackendStatus.Reason#ERROR ERROR} status.</li>
 *   <li>{@link #readInfo}, {@link #decode} and {@link #decodeThumbnail} validate the arguments, pass the data through
 *   {@link HeifInput#check} (no other format and no truncated file reaches the system decoder), fail with an
 *   {@link IOException} while the backend is unavailable, then call {@link #doReadInfo} / {@link #doDecode} /
 *   {@link #doDecodeThumbnail}, and turn a {@link RuntimeException} or {@link LinkageError} of the native layer into
 *   an {@link IOException}.</li>
 * </ul>
 */
public abstract class AbstractHeifBackend implements HeifBackend {
  private final Object lock = new Object();
  private volatile HeifBackendStatus status;

  /**
   * Checks whether the system decoder can be used: loads the native libraries and asks them, but decodes nothing
   * (or at most a tiny embedded sample). Runs once per {@link #status()} cache on a background thread, but possibly while
   * the IDE starts, so it must be fast (a few milliseconds, at most ~50 ms). May throw: an exception becomes an
   * {@code ERROR} status. Must not show any UI (the plugin prompts the user when the status says so).
   */
  protected abstract @NotNull HeifBackendStatus probe();

  /** {@link #readInfo} after the checks: {@code data} is complete HEIF data and the backend is available. */
  protected abstract @NotNull HeifImageInfo doReadInfo(byte[] data) throws IOException;

  /**
   * {@link #decode} after the checks: {@code data} is complete HEIF data, the backend is available and
   * {@code maxPixelSize >= 0}.
   */
  protected abstract @NotNull BufferedImage doDecode(byte[] data, int maxPixelSize) throws IOException;

  /**
   * {@link #decodeThumbnail} after the checks ({@code maxPixelSize > 0}). The default decodes the primary image at the
   * requested size; override it when the decoder can use the thumbnail embedded in the file.
   */
  protected @NotNull BufferedImage doDecodeThumbnail(byte[] data, int maxPixelSize) throws IOException {
    return doDecode(data, maxPixelSize);
  }

  @Override
  public final @NotNull HeifBackendStatus status() {
    HeifBackendStatus result = status;
    if (result != null) return result;
    synchronized (lock) {
      if (status == null) status = safeProbe();
      return status;
    }
  }

  @Override
  public final @NotNull HeifBackendStatus recheckStatus() {
    synchronized (lock) {
      status = safeProbe();
      return status;
    }
  }

  /** The cached status, or {@code null} if {@link #status()} has not been called yet (never probes). */
  @Override
  public final @Nullable HeifBackendStatus cachedStatus() {
    return status;
  }

  @Override
  public final @NotNull HeifImageInfo readInfo(byte[] data) throws IOException {
    HeifInput.check(data);
    requireAvailable();
    HeifImageInfo info;
    try {
      info = doReadInfo(data);
    }
    catch (RuntimeException | LinkageError e) {
      throw nativeFailure(e);
    }
    if (info == null) throw new IOException(displayName() + " returned no image properties");
    return info;
  }

  @Override
  public final @NotNull BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
    if (maxPixelSize < 0) throw new IllegalArgumentException("maxPixelSize must be >= 0: " + maxPixelSize);
    HeifInput.check(data);
    requireAvailable();
    BufferedImage image;
    try {
      image = doDecode(data, maxPixelSize);
    }
    catch (RuntimeException | LinkageError e) {
      throw nativeFailure(e);
    }
    if (image == null) throw new IOException(displayName() + " returned no image");
    return image;
  }

  @Override
  public final @NotNull BufferedImage decodeThumbnail(byte[] data, int maxPixelSize) throws IOException {
    if (maxPixelSize <= 0) throw new IllegalArgumentException("maxPixelSize must be > 0: " + maxPixelSize);
    HeifInput.check(data);
    requireAvailable();
    BufferedImage image;
    try {
      image = doDecodeThumbnail(data, maxPixelSize);
    }
    catch (RuntimeException | LinkageError e) {
      throw nativeFailure(e);
    }
    if (image == null) throw new IOException(displayName() + " returned no thumbnail");
    return image;
  }

  /** Throws an {@link IOException} that explains why decoding is impossible unless the backend is available. */
  protected final void requireAvailable() throws IOException {
    HeifBackendStatus current = status();
    if (!current.isAvailable()) throw new IOException(displayName() + " cannot decode HEIF images: " + current);
  }

  private IOException nativeFailure(Throwable t) {
    return new IOException(displayName() + " failed: " + t, t);
  }

  private HeifBackendStatus safeProbe() {
    try {
      HeifBackendStatus result = probe();
      return result != null ? result : HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "The probe returned no status");
    }
    catch (RuntimeException | LinkageError e) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "The probe failed: " + e);
    }
  }

  @Override
  public String toString() {
    return displayName();
  }
}
