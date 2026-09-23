package cn.yooss.heic;

import com.intellij.openapi.diagnostic.Logger;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code javax.imageio} service provider for HEIC/HEIF images.
 * <p>
 * The IDE's image viewer ({@code IfsUtil}) and image-info index ({@code ImageInfoReader}) pick the first reader
 * whose {@link #canDecodeInput(Object)} accepts the bytes, and show {@code getFormatName()} (= {@code names[0]},
 * {@code "heic"}) in the editor's info label. {@code canDecodeInput} is called for <em>every</em> image the IDE
 * loads, so it only runs the pure-Java {@link HeifSniffer} and never throws; native code is touched only when a
 * HEIC image is actually read.
 * <p>
 * The provider is registered programmatically by {@link HeicSupport}; there is deliberately no
 * {@code META-INF/services} entry (HeicSupport offers one to {@code ImageIO.scanForPlugins()} only when ImageIO uses a
 * registry other than {@code IIORegistry.getDefaultInstance()}).
 */
public final class HeicImageReaderSpi extends ImageReaderSpi {
  static final String[] FORMAT_NAMES = {"heic", "HEIC", "heif", "HEIF"};
  /** File extensions mapped to the "Image" file type (see META-INF/heic-viewer-macos.xml). */
  public static final String[] SUFFIXES = {"heic", "heif", "hif", "heics"};
  static final String[] MIME_TYPES = {"image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence"};
  /** Larger inputs are left to other readers (and would not be worth decoding into an IDE preview). */
  public static final long MAX_INPUT_BYTES = 512L * 1024 * 1024;
  /** Bound for {@link #REPORTED_FAILURES}. */
  private static final int MAX_REPORTED_FAILURES = 16;
  /** Causes of failures swallowed by {@link #canDecodeInput} that have been logged already (once per cause). */
  private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

  private final Supplier<DecodeLimits> limits;
  private final HeicBackend backend;
  /** The registry this provider was last registered in (see {@link #onRegistration}). */
  private volatile ServiceRegistry registry;

  /**
   * Provider with the {@link HeicSettings#decodeLimits() limits from the IDE settings} ({@link DecodeLimits#DEFAULT}
   * outside the IDE). Also the constructor {@code ServiceLoader} uses when {@link HeicSupport} registers the provider
   * through {@code ImageIO.scanForPlugins()}.
   */
  public HeicImageReaderSpi() {
    this(HeicSettings::decodeLimits);
  }

  /** @param limits queried on every read, so a changed setting applies to the next image */
  public HeicImageReaderSpi(Supplier<DecodeLimits> limits) {
    this(limits, HeicBackend.MAC_IMAGE_IO);
  }

  HeicImageReaderSpi(Supplier<DecodeLimits> limits, HeicBackend backend) {
    super("ZhuJHua", "1.0", FORMAT_NAMES.clone(), SUFFIXES.clone(), MIME_TYPES.clone(),
          HeicImageReader.class.getName(), new Class<?>[]{ImageInputStream.class},
          null, false, null, null, null, null, false, null, null, null, null);
    this.limits = Objects.requireNonNull(limits, "limits");
    this.backend = Objects.requireNonNull(backend, "backend");
  }

  /** The decoder needs macOS ImageIO.framework and the FFM API (Java 22+). */
  public static boolean isSupportedPlatform() {
    String os = System.getProperty("os.name", "");
    return os.toLowerCase(Locale.ROOT).startsWith("mac") && Runtime.version().feature() >= 22;
  }

  @Override
  public boolean canDecodeInput(Object source) {
    if (!(source instanceof ImageInputStream stream)) return false;
    try {
      long length = stream.length();
      if (length > MAX_INPUT_BYTES) return false;

      byte[] header = new byte[HeifSniffer.MAX_HEADER_BYTES];
      stream.mark();
      try {
        int n = readFully(stream, header, 0, 8);
        int needed = HeifSniffer.headerBytesNeeded(header, n);
        if (needed <= n) return false;
        n += readFully(stream, header, n, needed - n);
        return HeifSniffer.isHeif(header, n);
      }
      finally {
        stream.reset();
      }
    }
    catch (Throwable t) {
      // ImageIO only tolerates IOException here; anything else would break loading of every other image format.
      reportSwallowed(t);
      return false;
    }
  }

  /**
   * Logs a failure swallowed by {@link #canDecodeInput} as a warning with its stack trace, once per cause (exception
   * class and throwing frame), so that a HEIC file that silently shows "Image not loaded" can be diagnosed from
   * idea.log. Never throws.
   *
   * @return {@code true} if the failure was logged
   */
  static boolean reportSwallowed(Throwable failure) {
    try {
      StackTraceElement[] trace = failure.getStackTrace();
      String cause = failure.getClass().getName() + (trace.length > 0 ? " at " + trace[0] : "");
      if (REPORTED_FAILURES.size() >= MAX_REPORTED_FAILURES || !REPORTED_FAILURES.add(cause)) return false;
      Logger.getInstance(HeicImageReaderSpi.class)
        .warn("HEIC detection failed; the image is left to other readers (logged once per cause)", failure);
      return true;
    }
    catch (Throwable ignored) {
      return false; // logging must never make canDecodeInput throw
    }
  }

  private static int readFully(ImageInputStream stream, byte[] buffer, int offset, int length) throws java.io.IOException {
    int total = 0;
    while (total < length) {
      int read = stream.read(buffer, offset + total, length - total);
      if (read <= 0) break;
      total += read;
    }
    return total;
  }

  @Override
  public ImageReader createReaderInstance(Object extension) {
    return new HeicImageReader(this, limits, backend);
  }

  /** Remembers the registry, so that {@link HeicSupport} can deregister an instance ImageIO created itself. */
  @Override
  public void onRegistration(ServiceRegistry registry, Class<?> category) {
    this.registry = registry;
  }

  @Override
  public void onDeregistration(ServiceRegistry registry, Class<?> category) {
    if (this.registry == registry) this.registry = null;
  }

  /** The registry this provider is registered in, or {@code null}. */
  ServiceRegistry registry() {
    return registry;
  }

  @Override
  public String getDescription(Locale locale) {
    return "HEIC/HEIF image reader (macOS ImageIO.framework)";
  }
}
