package cn.yooss.heic.mac;

import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.HeifInput;
import cn.yooss.heic.backend.PixelPipeline;
import cn.yooss.heic.backend.PixelPipeline.ByteLayout;
import cn.yooss.heic.backend.PlaneConverter;
import cn.yooss.heic.mac.jna.JnaMacApi;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Set;

/**
 * Decodes HEIC/HEIF files into {@link BufferedImage}s with macOS ImageIO.framework.
 * <p>
 * ImageIO.framework picks the codec by content, so only HEIF data may reach it, whoever the caller is (the image
 * reader, whatever way it was looked up, or the thumbnail icons): {@link HeifInput#check} rejects anything whose header
 * is not a HEIC/HEIF {@code ftyp} box (pure Java) and truncated files before any native call, and the image source must
 * report a HEIF-family type.
 * <p>
 * Algorithm (see README): {@code CFDataCreate} -> {@code CGImageSourceCreateWithData(ShouldCache=false)} ->
 * primary image -> properties -> {@code CGImageSourceCreateThumbnailAtIndex(FromImageAlways, WithTransform,
 * ThumbnailMaxPixelSize, ShouldCacheImmediately)} so that the HEIF {@code irot}/{@code imir} and EXIF orientation
 * are applied -> drawn in strips of ~1M pixels, but at most {@link #MAX_DRAWS} of them (cropped with
 * {@code CGImageCreateWithImageInRect}), into an explicit 8-bit sRGB bitmap context -> copied (in ~1M-pixel chunks)
 * into a {@code TYPE_INT_RGB} image, or a non-premultiplied {@code TYPE_INT_ARGB} image when the file has alpha. An
 * image with alpha that is requested smaller is decoded at full size and downscaled alpha-weighted by
 * {@link PlaneConverter} instead of by ImageIO ({@link #isAlphaWeighted}).
 * <p>
 * The algorithm is written once against {@link MacApi}; the binding is the IDE's JNA ({@link JnaMacApi}), initialized
 * on the first decode. Every call is self-contained (own autorelease pool, every CF object and native buffer released
 * in {@code finally}), so the class is thread-safe. All failures, including a missing native layer, surface as
 * {@link IOException}; only {@link OutOfMemoryError} and other VM errors propagate.
 */
public final class HeicDecoder {
  /** Type identifiers of ImageIO.framework's ISO-BMFF image formats (the HEIF family). */
  private static final Set<String> HEIF_TYPES =
      Set.of("public.heic", "public.heics", "public.heif", "public.avci", "public.avif", "public.avis");

  private HeicDecoder() {
  }

  /** Name of the native binding, e.g. {@code "JNA 5.17.0 (arm64 HFA)"}; initializes it if needed. */
  public static String nativeBridge() throws IOException {
    return bound().api.name();
  }

  /** Reads the size, orientation and alpha of the primary image without decoding pixels. */
  public static HeifImageInfo readInfo(byte[] data) throws IOException {
    HeifInput.check(data);
    try (Session session = new Session(bound())) {
      session.open(data);
      return session.info();
    }
    catch (RuntimeException | LinkageError e) {
      throw nativeFailure(e);
    }
  }

  /**
   * Decodes the primary image with orientation applied.
   *
   * @param maxPixelSize {@code 0} for full resolution, otherwise the maximum length of the longer side of the
   *                     result (the image is downscaled while decoding, aspect ratio preserved, never upscaled)
   */
  public static BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
    if (maxPixelSize < 0) throw new IllegalArgumentException("maxPixelSize must be >= 0: " + maxPixelSize);
    return decode(data, maxPixelSize, false, PixelPipeline.STRIP_PIXELS);
  }

  /**
   * Fast, small preview of the primary image (orientation applied): uses the thumbnail embedded in the file when
   * there is one, otherwise decodes and downscales the image. Intended for icons/thumbnails, not for viewing:
   * the result may be smaller than {@code maxPixelSize} when the embedded thumbnail is smaller.
   *
   * @param maxPixelSize maximum length of the longer side of the result, {@code > 0}
   */
  public static BufferedImage decodeThumbnail(byte[] data, int maxPixelSize) throws IOException {
    if (maxPixelSize <= 0) throw new IllegalArgumentException("maxPixelSize must be > 0: " + maxPixelSize);
    return decode(data, maxPixelSize, true, PixelPipeline.STRIP_PIXELS);
  }

  /** @param stripPixels pixels rendered per strip (tests use other values to compare strip layouts) */
  static BufferedImage decode(byte[] data, int maxPixelSize, boolean allowEmbeddedThumbnail, int stripPixels) throws IOException {
    return decode(bound(), data, maxPixelSize, allowEmbeddedThumbnail, stripPixels);
  }

  /** @param bound the binding (tests wrap the real one to count native calls) */
  static BufferedImage decode(Bound bound, byte[] data, int maxPixelSize, boolean allowEmbeddedThumbnail, int stripPixels)
    throws IOException {
    HeifInput.check(data);
    try (Session session = new Session(bound)) {
      session.open(data);
      HeifImageInfo info = session.info();
      if (isAlphaWeighted(info, maxPixelSize)) {
        long image = session.createThumbnail(thumbnailSide(info, 0), false); // full size, like the image viewer's decode
        return session.renderAlphaWeighted(image, maxPixelSize, stripPixels);
      }
      long image = session.createThumbnail(thumbnailSide(info, maxPixelSize), allowEmbeddedThumbnail);
      return session.render(image, info.hasAlpha(), stripPixels);
    }
    catch (RuntimeException | LinkageError e) {
      throw nativeFailure(e);
    }
  }

  /**
   * The rows drawn per strip and the rows copied into the Java image per chunk, for a {@code width x height} image:
   * strips of about {@code stripPixels} pixels, but at most {@link #MAX_DRAWS} strips (see there); chunks of at most
   * {@code stripPixels} pixels and never more than a strip.
   */
  static int[] stripPlan(int width, int height, int stripPixels) {
    int rows = PixelPipeline.stripRows(width, height, stripPixels); // 1 .. height
    int stripRows = Math.max(rows, (height + MAX_DRAWS - 1) / MAX_DRAWS); // at least 1/MAX_DRAWS of the rows
    return new int[]{stripRows, rows};
  }

  /**
   * Whether an image is downscaled by {@link PlaneConverter} (alpha-weighted) rather than by ImageIO.framework: an image
   * with alpha, requested smaller than it is, of at most {@link #ALPHA_WEIGHTED_MAX_PIXELS}. ImageIO's thumbnail scaler
   * does not weight the colors by alpha on every Mac: the color under transparent pixels (black in HEIC files) then
   * darkens every edge (red at a quarter alpha came out as 128 instead of 255 on the GitHub macOS 26 arm64 and macOS 15
   * Intel runners, as 191 on an M-series Mac). Larger images (which only the pixel budget downscales) keep ImageIO's
   * scaler, whose decode does not need the full-size image in memory a second time.
   */
  static boolean isAlphaWeighted(HeifImageInfo info, int maxPixelSize) {
    if (!info.hasAlpha() || maxPixelSize <= 0) return false;
    long width = info.rawWidth(), height = info.rawHeight();
    return Math.max(width, height) > maxPixelSize && width * height <= ALPHA_WEIGHTED_MAX_PIXELS;
  }

  /**
   * The {@code kCGImageSourceThumbnailMaxPixelSize} to request: the full size for {@code 0}, otherwise the requested
   * size, but large enough that the shorter side stays at least 2 pixels (ImageIO.framework returns no image for
   * smaller thumbnails) and never larger than the image.
   */
  static int thumbnailSide(HeifImageInfo info, int maxPixelSize) {
    int longest = Math.max(info.rawWidth(), info.rawHeight());
    if (maxPixelSize <= 0 || maxPixelSize >= longest) return longest;
    int shortest = Math.max(1, Math.min(info.rawWidth(), info.rawHeight()));
    long minimum = Math.max(3, (2L * longest + shortest - 1) / shortest);
    return (int) Math.min(longest, Math.max(maxPixelSize, minimum));
  }

  /** Whether ImageIO.framework read the data as a HEIF-family image (and not with another codec). */
  static boolean isHeifType(String typeIdentifier) {
    return typeIdentifier != null && HEIF_TYPES.contains(typeIdentifier);
  }

  private static IOException nativeFailure(Throwable t) {
    return new IOException("macOS ImageIO.framework is not available or failed: " + t, t);
  }

  private static Bound bound() throws IOException {
    try {
      return Default.BOUND;
    }
    catch (LinkageError e) { // ExceptionInInitializerError / NoClassDefFoundError of the lazy holder
      throw nativeFailure(e);
    }
  }

  /** Lazy holder: the JNA binding is created (and the frameworks are opened) on the first decode. */
  private static final class Default {
    static final Bound BOUND = new Bound(new JnaMacApi());
  }

  static final int kCGImageAlphaNone = 0;
  static final int kCGImageAlphaPremultipliedFirst = 2;
  static final int kCGImageAlphaNoneSkipLast = 5;
  static final int kCGImageAlphaNoneSkipFirst = 6;
  static final int kCGBitmapByteOrder32Little = 2 << 12;
  static final int kCGBlendModeCopy = 17;
  /**
   * The most strips one decode draws. A cropped view shares the decoded pixels only while ImageIO.framework keeps them
   * cached; when it cannot (e.g. a malformed file whose {@code ispe} does not match its coded image), the thumbnail is a
   * lazy image and every draw runs the whole HEIF decode again: with ~1M-pixel strips, a 64-megapixel image would be
   * decoded about 64 times (about a minute, not cancellable). Fewer, taller strips cost a larger native scratch buffer
   * (1/8 of the image: 32 MB at the default 64-megapixel budget), next to ImageIO's cached copy of the whole image.
   */
  static final int MAX_DRAWS = 8;
  /** Largest image with alpha that is downscaled alpha-weighted (the default pixel budget), see {@link #isAlphaWeighted}. */
  static final long ALPHA_WEIGHTED_MAX_PIXELS = 64_000_000L;

  /** A {@link MacApi} plus the constants the algorithm needs, resolved once. */
  static final class Bound {
    final MacApi api;
    final long kCFBooleanTrue;
    final long kCFBooleanFalse;
    final long kCGImageSourceShouldCache;
    final long kCGImageSourceShouldCacheImmediately;
    final long kCGImageSourceCreateThumbnailFromImageAlways;
    final long kCGImageSourceCreateThumbnailFromImageIfAbsent;
    final long kCGImageSourceCreateThumbnailWithTransform;
    final long kCGImageSourceThumbnailMaxPixelSize;
    final long kCGImagePropertyPixelWidth;
    final long kCGImagePropertyPixelHeight;
    final long kCGImagePropertyOrientation;
    final long kCGImagePropertyDepth;
    final long kCGImagePropertyHasAlpha;
    final long kCGColorSpaceSRGB;

    Bound(MacApi api) {
      this.api = api;
      kCFBooleanTrue = api.constant(MacApi.Framework.CORE_FOUNDATION, "kCFBooleanTrue");
      kCFBooleanFalse = api.constant(MacApi.Framework.CORE_FOUNDATION, "kCFBooleanFalse");
      kCGImageSourceShouldCache = imageIO("kCGImageSourceShouldCache");
      kCGImageSourceShouldCacheImmediately = imageIO("kCGImageSourceShouldCacheImmediately");
      kCGImageSourceCreateThumbnailFromImageAlways = imageIO("kCGImageSourceCreateThumbnailFromImageAlways");
      kCGImageSourceCreateThumbnailFromImageIfAbsent = imageIO("kCGImageSourceCreateThumbnailFromImageIfAbsent");
      kCGImageSourceCreateThumbnailWithTransform = imageIO("kCGImageSourceCreateThumbnailWithTransform");
      kCGImageSourceThumbnailMaxPixelSize = imageIO("kCGImageSourceThumbnailMaxPixelSize");
      kCGImagePropertyPixelWidth = imageIO("kCGImagePropertyPixelWidth");
      kCGImagePropertyPixelHeight = imageIO("kCGImagePropertyPixelHeight");
      kCGImagePropertyOrientation = imageIO("kCGImagePropertyOrientation");
      kCGImagePropertyDepth = imageIO("kCGImagePropertyDepth");
      kCGImagePropertyHasAlpha = imageIO("kCGImagePropertyHasAlpha");
      kCGColorSpaceSRGB = api.constant(MacApi.Framework.CORE_GRAPHICS, "kCGColorSpaceSRGB");
    }

    private long imageIO(String symbol) {
      return api.constant(MacApi.Framework.IMAGE_IO, symbol);
    }
  }

  /** One decode: an autorelease pool and the CF objects / native buffer to release, in that nesting. */
  private static final class Session implements AutoCloseable {
    private final Bound k;
    private final MacApi api;
    private final long pool;
    private final ArrayDeque<Long> owned = new ArrayDeque<>();
    private long buffer;
    private long source;
    private String type;
    private long properties;
    private long count;
    private long index;
    private HeifImageInfo info;

    Session(Bound bound) {
      k = bound;
      api = bound.api;
      pool = api.autoreleasePoolPush();
    }

    private long own(long ref) {
      if (ref != 0) owned.push(ref);
      return ref;
    }

    void open(byte[] bytes) throws IOException {
      long data = own(api.cfDataCreate(bytes));
      if (data == 0) throw new IOException("CFDataCreate failed");

      long options = dictionary(k.kCGImageSourceShouldCache, Boolean.FALSE);
      source = own(api.cgImageSourceCreateWithData(data, options));
      if (source == 0) throw new IOException("Not an image that ImageIO.framework can read");
      type = api.cfString(api.cgImageSourceGetType(source));
      if (!isHeifType(type)) throw new IOException("Not a HEIF image (ImageIO.framework type " + type + ")");

      count = api.cgImageSourceGetCount(source);
      if (count < 1) throw new IOException("No image found (image source status " + api.cgImageSourceGetStatus(source) + ")");
      long primary = api.cgImageSourceGetPrimaryImageIndex(source);
      index = primary >= 0 && primary < count ? primary : 0;

      properties = own(api.cgImageSourceCopyPropertiesAtIndex(source, index, 0));
      if (properties == 0) {
        throw new IOException("Cannot read image properties (image source status " + api.cgImageSourceGetStatus(source) + ")");
      }
    }

    HeifImageInfo info() throws IOException {
      if (info != null) return info;
      long width = property(k.kCGImagePropertyPixelWidth, -1);
      long height = property(k.kCGImagePropertyPixelHeight, -1);
      if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
        throw new IOException("Invalid image size " + width + "x" + height);
      }
      long orientation = property(k.kCGImagePropertyOrientation, 1);
      if (orientation < 1 || orientation > 8) orientation = 1;
      int depth = (int) property(k.kCGImagePropertyDepth, -1);
      // kCGImagePropertyHasAlpha is only present when the file has alpha. Thumbnails are always RGBA, so when the
      // property is missing, ask a lazily created (not yet decoded) image for its alpha info instead.
      long hasAlpha = property(k.kCGImagePropertyHasAlpha, -1);
      boolean alpha = hasAlpha >= 0 ? hasAlpha != 0 : lazyImageHasAlpha();
      info = new HeifImageInfo(type, (int) Math.min(count, Integer.MAX_VALUE), (int) index, (int) width, (int) height,
                               (int) orientation, depth, alpha);
      return info;
    }

    private long property(long key, long defaultValue) {
      return api.cfLongValue(api.cfDictionaryGetValue(properties, key), defaultValue);
    }

    private boolean lazyImageHasAlpha() {
      long lazy = api.cgImageSourceCreateImageAtIndex(source, index, 0);
      if (lazy == 0) return false;
      try {
        int alphaInfo = api.cgImageGetAlphaInfo(lazy);
        return alphaInfo != kCGImageAlphaNone && alphaInfo != kCGImageAlphaNoneSkipLast && alphaInfo != kCGImageAlphaNoneSkipFirst;
      }
      finally {
        api.cfRelease(lazy);
      }
    }

    long createThumbnail(int maxPixelSize, boolean allowEmbeddedThumbnail) throws IOException {
      long options = dictionary(
          allowEmbeddedThumbnail ? k.kCGImageSourceCreateThumbnailFromImageIfAbsent
                                 : k.kCGImageSourceCreateThumbnailFromImageAlways, Boolean.TRUE,
          k.kCGImageSourceCreateThumbnailWithTransform, Boolean.TRUE,
          k.kCGImageSourceThumbnailMaxPixelSize, (long) maxPixelSize,
          k.kCGImageSourceShouldCacheImmediately, Boolean.TRUE);
      long image = own(api.cgImageSourceCreateThumbnailAtIndex(source, index, options));
      if (image == 0) {
        throw new IOException("ImageIO.framework could not decode the image (image source status "
                              + api.cgImageSourceGetStatus(source) + ")");
      }
      return image;
    }

    /** Draws {@code image} into sRGB 8-bit strips and copies them into a new {@link BufferedImage}. */
    BufferedImage render(long image, boolean alpha, int stripPixels) throws IOException {
      BufferedImage out = PixelPipeline.newImage(api.cgImageGetWidth(image), api.cgImageGetHeight(image), alpha);
      int width = out.getWidth();
      int height = out.getHeight();

      int[] plan = stripPlan(width, height, stripPixels);
      int stripRows = plan[0];
      int copyRows = plan[1];
      long bytesPerRow = 4L * width;
      buffer = api.malloc(bytesPerRow * stripRows);
      if (buffer == 0) throw new IOException("Cannot allocate " + bytesPerRow * stripRows + " bytes of native memory");

      long colorSpace = own(api.cgColorSpaceCreateWithName(k.kCGColorSpaceSRGB));
      if (colorSpace == 0) throw new IOException("Cannot create the sRGB color space");
      // Little-endian 32-bit BGRA == int 0xAARRGGBB, i.e. the TYPE_INT_ARGB_PRE / TYPE_INT_RGB layouts.
      int bitmapInfo = (alpha ? kCGImageAlphaPremultipliedFirst : kCGImageAlphaNoneSkipFirst) | kCGBitmapByteOrder32Little;
      long context = own(api.cgBitmapContextCreate(buffer, width, stripRows, 8, bytesPerRow, colorSpace, bitmapInfo));
      if (context == 0) throw new IOException("Cannot create a " + width + "x" + stripRows + " bitmap context");
      api.cgContextSetBlendMode(context, kCGBlendModeCopy); // overwrite, never blend with stale rows

      int[] pixels = new int[width * copyRows];
      for (int y0 = 0; y0 < height; y0 += stripRows) {
        int rows = Math.min(stripRows, height - y0);
        drawStrip(context, image, width, height, y0, rows, stripRows);
        for (int r0 = 0; r0 < rows; r0 += copyRows) { // the Java side in small chunks, however tall the strip
          int n = Math.min(copyRows, rows - r0);
          api.readInts(buffer + r0 * bytesPerRow, pixels, width * n);
          PixelPipeline.writeArgbRows(out, y0 + r0, n, pixels, alpha); // un-premultiplies images with alpha
        }
      }
      return out;
    }

    /**
     * Draws rows {@code y0 .. y0+rows-1} of {@code image} into the top {@code rows} rows of {@code context}, which is
     * {@code stripRows} rows high.
     */
    private void drawStrip(long context, long image, int width, int height, int y0, int rows, int stripRows)
      throws IOException {
      if (stripRows < height) {
        // Drawing the whole image into a strip-sized context costs O(whole image) per strip; draw a cropped view into
        // the top `rows` rows of the context instead. The view shares the decoded pixels only if ImageIO cached them;
        // otherwise each draw decodes the whole image again, hence at most MAX_DRAWS strips.
        long strip = api.cgImageCreateWithImageInRect(image, 0, y0, width, rows); // image space, origin top-left
        if (strip == 0) throw new IOException("CGImageCreateWithImageInRect failed");
        try {
          api.cgContextDrawImage(context, 0, stripRows - rows, width, rows, strip); // context space, origin bottom-left
        }
        finally {
          api.cfRelease(strip);
        }
      }
      else {
        api.cgContextDrawImage(context, 0, 0, width, height, image);
      }
    }

    /**
     * Draws the full-size {@code image} (with alpha) into premultiplied sRGB strips, as {@link #render} does, and
     * downscales it to {@code maxPixelSize} with {@link PlaneConverter} (alpha-weighted box filter, then one bilinear
     * step) while reading the strips: the Java heap holds a strip and the reduced image, never the full-size image.
     */
    BufferedImage renderAlphaWeighted(long image, int maxPixelSize, int stripPixels) throws IOException {
      long imageWidth = api.cgImageGetWidth(image), imageHeight = api.cgImageGetHeight(image);
      if (imageWidth <= 0 || imageHeight <= 0 || imageWidth * imageHeight > Integer.MAX_VALUE / 4) {
        throw new IOException("Invalid decoded image size " + imageWidth + "x" + imageHeight);
      }
      int width = (int) imageWidth;
      int height = (int) imageHeight;
      int stripRows = stripPlan(width, height, stripPixels)[0];
      long bytesPerRow = 4L * width;
      buffer = api.malloc(bytesPerRow * stripRows);
      if (buffer == 0) throw new IOException("Cannot allocate " + bytesPerRow * stripRows + " bytes of native memory");
      long colorSpace = own(api.cgColorSpaceCreateWithName(k.kCGColorSpaceSRGB));
      if (colorSpace == 0) throw new IOException("Cannot create the sRGB color space");
      int bitmapInfo = kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little; // int 0xAARRGGBB, premultiplied
      long context = own(api.cgBitmapContextCreate(buffer, width, stripRows, 8, bytesPerRow, colorSpace, bitmapInfo));
      if (context == 0) throw new IOException("Cannot create a " + width + "x" + stripRows + " bitmap context");
      api.cgContextSetBlendMode(context, kCGBlendModeCopy); // overwrite, never blend with stale rows

      int[] drawn = {-1}; // the first row of the strip in the context
      int[][] pixels = {new int[0]};
      // PlaneConverter reads the rows in order, in chunks that need not match the strips.
      return PlaneConverter.convert(width, height, 4 * width, ByteLayout.RGBA, true, true, maxPixelSize, (y0, rows, target) -> {
        for (int done = 0; done < rows; ) {
          int y = y0 + done;
          int start = y / stripRows * stripRows;
          int stripHeight = Math.min(stripRows, height - start);
          if (drawn[0] != start) {
            drawStrip(context, image, width, height, start, stripHeight, stripRows);
            drawn[0] = start;
          }
          int n = Math.min(rows - done, start + stripHeight - y);
          if (pixels[0].length < width * n) pixels[0] = new int[width * n];
          int[] argb = pixels[0];
          api.readInts(buffer + (y - start) * bytesPerRow, argb, width * n);
          for (int i = 0, at = done * 4 * width; i < width * n; i++, at += 4) {
            int p = argb[i];
            target[at] = (byte) (p >> 16);
            target[at + 1] = (byte) (p >> 8);
            target[at + 2] = (byte) p;
            target[at + 3] = (byte) (p >>> 24);
          }
          done += n;
        }
      });
    }

    /** Builds a CFDictionary from key/value pairs; values are {@link Boolean} or {@link Long}. Released on close. */
    private long dictionary(Object... keyValues) {
      long dictionary = own(api.cfDictionaryCreateMutable(keyValues.length / 2));
      if (dictionary == 0) throw new IllegalStateException("CFDictionaryCreateMutable failed");
      for (int i = 0; i < keyValues.length; i += 2) {
        long key = (Long) keyValues[i];
        Object value = keyValues[i + 1];
        if (value instanceof Boolean) {
          api.cfDictionarySetValue(dictionary, key, (Boolean) value ? k.kCFBooleanTrue : k.kCFBooleanFalse);
        }
        else if (value instanceof Long) {
          long number = api.cfNumberCreateSInt64((Long) value);
          if (number == 0) throw new IllegalStateException("CFNumberCreate failed");
          try {
            api.cfDictionarySetValue(dictionary, key, number); // the dictionary retains the number
          }
          finally {
            api.cfRelease(number);
          }
        }
        else {
          throw new IllegalArgumentException("Unsupported dictionary value: " + value);
        }
      }
      return dictionary;
    }

    @Override
    public void close() {
      try {
        while (!owned.isEmpty()) {
          api.cfRelease(owned.pop()); // the bitmap context is released before the buffer it draws into
        }
      }
      finally {
        try {
          api.free(buffer);
        }
        finally {
          api.autoreleasePoolPop(pool);
        }
      }
    }
  }
}
