package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * A system HEIF decoder: macOS ImageIO.framework ({@code cn.yooss.heic.mac}), Windows WIC with the Microsoft Store HEIF
 * and HEVC extensions ({@code cn.yooss.heic.win}) or the system libheif on Linux ({@code cn.yooss.heic.linux}). The
 * plugin never bundles a decoder or a native library; native code is reached through the IDE's bundled JNA (see
 * {@link cn.yooss.heic.backend.jna.JnaLibraries} for the rules that keep the plugin unloadable).
 * <p>
 * {@link HeifBackends#current()} picks the implementation for the running OS. Everything outside the backend packages
 * (the {@code javax.imageio} reader, the thumbnails, the registration and the install prompt) only uses this interface.
 * <p>
 * <b>Contract</b> (checked by {@code HeifBackendContractTest} on every OS where the backend is available; extend
 * {@link AbstractHeifBackend}, which implements the OS-independent parts):
 * <ul>
 *   <li>Implementations are thread-safe; decodes may run concurrently (image editor, diff, two thumbnail threads).</li>
 *   <li>Constructing a backend and calling {@link #id()} / {@link #displayName()} never loads native code.
 *   {@link #status()} probes once and caches the result (see {@link AbstractHeifBackend}); the probe may load native
 *   libraries but must be fast (a few milliseconds, at most ~50 ms): it runs while the IDE starts.</li>
 *   <li>Every decode method first passes the data through {@link HeifInput#check} (pure Java: HEIF {@code ftyp}
 *   sniffing and a truncation check), so that no other format and no truncated file ever reaches a system codec, which
 *   would pick a codec by content or "successfully" decode a black image.</li>
 *   <li>Results are 8-bit sRGB {@link BufferedImage}s with the orientation applied (EXIF orientation and HEIF
 *   {@code irot}/{@code imir}): {@code TYPE_INT_RGB} for opaque images, non-premultiplied {@code TYPE_INT_ARGB} when
 *   {@link HeifImageInfo#hasAlpha()}. {@link PixelPipeline} converts native buffers, un-premultiplies, applies the
 *   orientation and converts ICC profiles for backends that cannot do that natively.</li>
 *   <li>The size of {@code decode(data, 0)} equals {@link HeifImageInfo#width()} x {@link HeifImageInfo#height()}.</li>
 *   <li>All failures are reported as {@link IOException} (including a missing or broken native layer: wrap
 *   {@link RuntimeException}s and {@link LinkageError}s); only {@link OutOfMemoryError} and other VM errors propagate.
 *   Decoding when {@link #status()} is unavailable fails with an {@link IOException}.</li>
 * </ul>
 */
public interface HeifBackend {
  /** Stable machine-readable id for logs and tests, e.g. {@code macos-imageio}, {@code windows-wic}. */
  @NotNull String id();

  /** English name for idea.log and the reader description, e.g. {@code macOS ImageIO.framework}. */
  @NotNull String displayName();

  /**
   * Whether the system decoder can be used. The first call probes (it may load native libraries), later calls return
   * the cached result. Never throws.
   */
  @NotNull HeifBackendStatus status();

  /**
   * The status {@link #status()} has cached, or {@code null} if the backend has not been probed yet. Never probes and
   * never loads native code, so the UI may call it on any thread, including the EDT. The default knows nothing.
   */
  default @Nullable HeifBackendStatus cachedStatus() {
    return null;
  }

  /**
   * Forgets the cached status and probes again, e.g. after the user installed the missing system component
   * ("Check again" in the install prompt). Never throws.
   */
  @NotNull HeifBackendStatus recheckStatus();

  /** Size, orientation and alpha of the primary image without decoding pixels. */
  @NotNull HeifImageInfo readInfo(byte[] data) throws IOException;

  /**
   * Decodes the primary image with the orientation applied.
   *
   * @param maxPixelSize {@code 0} for full resolution, otherwise the maximum length of the longer side of the result
   *                     (downscaled while or after decoding, aspect ratio preserved, never upscaled; rounding by one
   *                     pixel is tolerated)
   */
  @NotNull BufferedImage decode(byte[] data, int maxPixelSize) throws IOException;

  /**
   * The Java heap {@link #decode decode(data, maxPixelSize)} of an image with {@code info} needs at its peak: the result
   * (4 bytes per pixel) and the backend's transient heap buffers (strips, an alpha plane, the copy that applies an
   * orientation, ...), not counting the input data and never the native memory of the system decoder. The image
   * reader's heap safety valve ({@code cn.yooss.heic.HeapValve}) decides the decode size with it before decoding;
   * {@code HeifBackendContractTest} checks that a decode allocates no more. Pure arithmetic: never loads native code.
   * The default is the result plus {@link HeapCost#FIXED_BYTES}.
   *
   * @param maxPixelSize as for {@link #decode}: {@code 0} for full resolution
   */
  default long decodeHeapBytes(@NotNull HeifImageInfo info, int maxPixelSize) {
    return HeapCost.simple(info, maxPixelSize);
  }

  /**
   * Fast, small preview of the primary image for icons (orientation applied): may use the thumbnail embedded in the
   * file, so the result may be smaller than requested, but its longer side is never larger than {@code maxPixelSize}
   * (rounding by one pixel is tolerated).
   *
   * @param maxPixelSize maximum length of the longer side of the result, {@code > 0}
   */
  @NotNull BufferedImage decodeThumbnail(byte[] data, int maxPixelSize) throws IOException;

  /**
   * Releases native resources before the plugin is unloaded (called once, from {@code beforePluginUnload}). Decoding
   * afterwards may fail. The default does nothing.
   */
  default void dispose() {
  }
}
