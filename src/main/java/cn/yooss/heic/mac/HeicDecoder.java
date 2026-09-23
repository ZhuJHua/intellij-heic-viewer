package cn.yooss.heic.mac;

import cn.yooss.heic.HeifSniffer;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;

import static cn.yooss.heic.mac.MacImageIO.isNull;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Decodes HEIC/HEIF files into {@link BufferedImage}s with macOS ImageIO.framework.
 * <p>
 * ImageIO.framework picks the codec by content, so only HEIF data may reach it, whoever the caller is (the image
 * reader, whatever way it was looked up, or the thumbnail icons): {@link #checkInput} rejects anything whose header is
 * not a HEIC/HEIF {@code ftyp} box ({@link HeifSniffer}, pure Java) before any native call, and the image source must
 * report a HEIF-family type.
 * <p>
 * Algorithm (see README): {@code CFDataCreate} -> {@code CGImageSourceCreateWithData(ShouldCache=false)} ->
 * primary image -> properties -> {@code CGImageSourceCreateThumbnailAtIndex(FromImageAlways, WithTransform,
 * ThumbnailMaxPixelSize, ShouldCacheImmediately)} so that the HEIF {@code irot}/{@code imir} and EXIF orientation
 * are applied -> drawn in ~1M-pixel strips (cropped with {@code CGImageCreateWithImageInRect}) into an explicit
 * 8-bit sRGB bitmap context -> copied into a {@code TYPE_INT_RGB} image, or a non-premultiplied
 * {@code TYPE_INT_ARGB} image when the file has alpha.
 * <p>
 * Every call is self-contained (own autorelease pool, own confined arena, every CF object released in
 * {@code finally}), so the class is thread-safe. All failures, including a missing native layer, surface as
 * {@link IOException}; only {@link OutOfMemoryError} and other VM errors propagate.
 */
public final class HeicDecoder {
  /** Pixels per rendered strip: bounds the native scratch buffer to 4 MB. */
  private static final int STRIP_PIXELS = 1 << 20;
  /** A {@code TYPE_INT_*} {@link BufferedImage} is backed by a single {@code int[]}. */
  private static final long MAX_IMAGE_PIXELS = Integer.MAX_VALUE - 16;
  /** Type identifiers of ImageIO.framework's ISO-BMFF image formats (the HEIF family). */
  private static final Set<String> HEIF_TYPES =
      Set.of("public.heic", "public.heics", "public.heif", "public.avci", "public.avif", "public.avis");

  private HeicDecoder() {
  }

  /**
   * Cheap image metadata of the primary image (no pixel decoding).
   *
   * @param typeIdentifier UTI reported by ImageIO.framework, e.g. {@code public.heic} or {@code public.heics}
   * @param rawWidth       width as stored, before orientation
   * @param rawHeight      height as stored, before orientation
   * @param orientation    EXIF-style orientation 1..8 (HEIF {@code irot}/{@code imir} are reported the same way)
   * @param bitDepth       bits per component as reported by the file, or -1 if unknown
   */
  public record Info(String typeIdentifier, int imageCount, int primaryIndex, int rawWidth, int rawHeight,
                     int orientation, int bitDepth, boolean hasAlpha) {
    /** Orientations 5..8 rotate by 90 degrees, so the displayed image has width and height swapped. */
    public boolean swapsAxes() {
      return orientation >= 5 && orientation <= 8;
    }

    /** Display width (orientation applied). */
    public int width() {
      return swapsAxes() ? rawHeight : rawWidth;
    }

    /** Display height (orientation applied). */
    public int height() {
      return swapsAxes() ? rawWidth : rawHeight;
    }
  }

  /** Reads the size, orientation and alpha of the primary image without decoding pixels. */
  public static Info readInfo(byte[] data) throws IOException {
    checkInput(data);
    try (Session session = new Session()) {
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
    return decode(data, maxPixelSize, false, STRIP_PIXELS);
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
    return decode(data, maxPixelSize, true, STRIP_PIXELS);
  }

  /** @param stripPixels pixels rendered per strip (tests use other values to compare strip layouts) */
  static BufferedImage decode(byte[] data, int maxPixelSize, boolean allowEmbeddedThumbnail, int stripPixels) throws IOException {
    checkInput(data);
    try (Session session = new Session()) {
      session.open(data);
      Info info = session.info();
      MemorySegment image = session.createThumbnail(thumbnailSide(info, maxPixelSize), allowEmbeddedThumbnail);
      return session.render(image, info.hasAlpha(), stripPixels);
    }
    catch (RuntimeException | LinkageError e) {
      throw nativeFailure(e);
    }
  }

  /**
   * The {@code kCGImageSourceThumbnailMaxPixelSize} to request: the full size for {@code 0}, otherwise the requested
   * size, but large enough that the shorter side stays at least 2 pixels (ImageIO.framework returns no image for
   * smaller thumbnails) and never larger than the image.
   */
  static int thumbnailSide(Info info, int maxPixelSize) {
    int longest = Math.max(info.rawWidth(), info.rawHeight());
    if (maxPixelSize <= 0 || maxPixelSize >= longest) return longest;
    int shortest = Math.max(1, Math.min(info.rawWidth(), info.rawHeight()));
    long minimum = Math.max(3, (2L * longest + shortest - 1) / shortest);
    return (int) Math.min(longest, Math.max(maxPixelSize, minimum));
  }

  private static void checkInput(byte[] data) throws IOException {
    if (data == null || data.length == 0) throw new IOException("Empty image data");
    if (!HeifSniffer.isHeif(data, data.length)) throw new IOException("Not a HEIC/HEIF file");
    String truncation = IsoBoxes.findTruncation(data);
    if (truncation != null) throw new IOException("Truncated or corrupt HEIF file: " + truncation);
  }

  /** Whether ImageIO.framework read the data as a HEIF-family image (and not with another codec). */
  static boolean isHeifType(String typeIdentifier) {
    return typeIdentifier != null && HEIF_TYPES.contains(typeIdentifier);
  }

  private static IOException nativeFailure(Throwable t) {
    return new IOException("macOS ImageIO.framework is not available or failed: " + t, t);
  }

  /** One decode: an autorelease pool, a confined arena and the CF objects to release, in that nesting. */
  private static final class Session implements AutoCloseable {
    private final Arena arena;
    private final MemorySegment pool;
    private final ArrayDeque<MemorySegment> owned = new ArrayDeque<>();
    private MemorySegment source;
    private String type;
    private MemorySegment properties;
    private long count;
    private long index;
    private Info info;

    Session() {
      pool = MacImageIO.autoreleasePoolPush(); // first touch of MacImageIO: may throw LinkageError
      arena = Arena.ofConfined();
    }

    private MemorySegment own(MemorySegment ref) {
      if (!isNull(ref)) owned.push(ref);
      return ref;
    }

    void open(byte[] bytes) throws IOException {
      MemorySegment nativeBytes = arena.allocateFrom(JAVA_BYTE, bytes);
      MemorySegment data = own(MacImageIO.cfDataCreate(nativeBytes, bytes.length));
      if (isNull(data)) throw new IOException("CFDataCreate failed");

      MemorySegment options = dictionary(MacImageIO.kCGImageSourceShouldCache, Boolean.FALSE);
      source = own(MacImageIO.cgImageSourceCreateWithData(data, options));
      if (isNull(source)) throw new IOException("Not an image that ImageIO.framework can read");
      type = MacImageIO.cfString(arena, MacImageIO.cgImageSourceGetType(source));
      if (!isHeifType(type)) throw new IOException("Not a HEIF image (ImageIO.framework type " + type + ")");

      count = MacImageIO.cgImageSourceGetCount(source);
      if (count < 1) throw new IOException("No image found (image source status " + MacImageIO.cgImageSourceGetStatus(source) + ")");
      long primary = MacImageIO.cgImageSourceGetPrimaryImageIndex(source);
      index = primary >= 0 && primary < count ? primary : 0;

      properties = own(MacImageIO.cgImageSourceCopyPropertiesAtIndex(source, index, MemorySegment.NULL));
      if (isNull(properties)) {
        throw new IOException("Cannot read image properties (image source status " + MacImageIO.cgImageSourceGetStatus(source) + ")");
      }
    }

    Info info() throws IOException {
      if (info != null) return info;
      long width = property(MacImageIO.kCGImagePropertyPixelWidth, -1);
      long height = property(MacImageIO.kCGImagePropertyPixelHeight, -1);
      if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
        throw new IOException("Invalid image size " + width + "x" + height);
      }
      long orientation = property(MacImageIO.kCGImagePropertyOrientation, 1);
      if (orientation < 1 || orientation > 8) orientation = 1;
      int depth = (int) property(MacImageIO.kCGImagePropertyDepth, -1);
      // kCGImagePropertyHasAlpha is only present when the file has alpha. Thumbnails are always RGBA, so when the
      // property is missing, ask a lazily created (not yet decoded) image for its alpha info instead.
      long hasAlpha = property(MacImageIO.kCGImagePropertyHasAlpha, -1);
      boolean alpha = hasAlpha >= 0 ? hasAlpha != 0 : lazyImageHasAlpha();
      info = new Info(type, (int) Math.min(count, Integer.MAX_VALUE), (int) index, (int) width, (int) height,
                      (int) orientation, depth, alpha);
      return info;
    }

    private long property(MemorySegment key, long defaultValue) {
      return MacImageIO.cfLongValue(arena, MacImageIO.cfDictionaryGetValue(properties, key), defaultValue);
    }

    private boolean lazyImageHasAlpha() {
      MemorySegment lazy = MacImageIO.cgImageSourceCreateImageAtIndex(source, index, MemorySegment.NULL);
      if (isNull(lazy)) return false;
      try {
        int alphaInfo = MacImageIO.cgImageGetAlphaInfo(lazy);
        return alphaInfo != MacImageIO.kCGImageAlphaNone
               && alphaInfo != MacImageIO.kCGImageAlphaNoneSkipLast
               && alphaInfo != MacImageIO.kCGImageAlphaNoneSkipFirst;
      }
      finally {
        MacImageIO.cfRelease(lazy);
      }
    }

    MemorySegment createThumbnail(int maxPixelSize, boolean allowEmbeddedThumbnail) throws IOException {
      MemorySegment options = dictionary(
          allowEmbeddedThumbnail ? MacImageIO.kCGImageSourceCreateThumbnailFromImageIfAbsent
                                 : MacImageIO.kCGImageSourceCreateThumbnailFromImageAlways, Boolean.TRUE,
          MacImageIO.kCGImageSourceCreateThumbnailWithTransform, Boolean.TRUE,
          MacImageIO.kCGImageSourceThumbnailMaxPixelSize, (long) maxPixelSize,
          MacImageIO.kCGImageSourceShouldCacheImmediately, Boolean.TRUE);
      MemorySegment image = own(MacImageIO.cgImageSourceCreateThumbnailAtIndex(source, index, options));
      if (isNull(image)) {
        throw new IOException("ImageIO.framework could not decode the image (image source status "
                              + MacImageIO.cgImageSourceGetStatus(source) + ")");
      }
      return image;
    }

    /** Draws {@code image} into sRGB 8-bit strips and copies them into a new {@link BufferedImage}. */
    BufferedImage render(MemorySegment image, boolean alpha, int stripPixels) throws IOException {
      long w = MacImageIO.cgImageGetWidth(image);
      long h = MacImageIO.cgImageGetHeight(image);
      if (w <= 0 || h <= 0 || w * h > MAX_IMAGE_PIXELS) {
        throw new IOException(String.format(Locale.ROOT, "Decoded image size %dx%d is not supported", w, h));
      }
      int width = (int) w;
      int height = (int) h;
      BufferedImage out = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

      int stripRows = Math.max(1, Math.min(height, stripPixels / width));
      long bytesPerRow = 4L * width;
      MemorySegment buffer = arena.allocate(bytesPerRow * stripRows, 16);

      MemorySegment colorSpace = own(MacImageIO.cgColorSpaceCreateWithName(MacImageIO.kCGColorSpaceSRGB));
      if (isNull(colorSpace)) throw new IOException("Cannot create the sRGB color space");
      // Little-endian 32-bit BGRA == int 0xAARRGGBB, i.e. the TYPE_INT_ARGB_PRE / TYPE_INT_RGB layouts.
      int bitmapInfo = (alpha ? MacImageIO.kCGImageAlphaPremultipliedFirst : MacImageIO.kCGImageAlphaNoneSkipFirst)
                       | MacImageIO.kCGBitmapByteOrder32Little;
      MemorySegment context = own(MacImageIO.cgBitmapContextCreate(buffer, width, stripRows, 8, bytesPerRow, colorSpace, bitmapInfo));
      if (isNull(context)) throw new IOException("Cannot create a " + width + "x" + stripRows + " bitmap context");
      MacImageIO.cgContextSetBlendMode(context, MacImageIO.kCGBlendModeCopy); // overwrite, never blend with stale rows

      MemorySegment rect = arena.allocate(MacImageIO.CG_RECT);
      int[] pixels = new int[width * stripRows];
      WritableRaster raster = out.getRaster();
      for (int y0 = 0; y0 < height; y0 += stripRows) {
        int rows = Math.min(stripRows, height - y0);
        if (stripRows < height) {
          // Drawing the whole image into a strip-sized context costs O(whole image) per strip; draw a cropped
          // view (it shares the already decoded pixels) into the top `rows` rows of the context instead.
          MacImageIO.setRect(rect, 0, y0, width, rows); // image space, origin top-left
          MemorySegment strip = MacImageIO.cgImageCreateWithImageInRect(image, rect);
          if (isNull(strip)) throw new IOException("CGImageCreateWithImageInRect failed");
          try {
            MacImageIO.setRect(rect, 0, stripRows - rows, width, rows); // context space, origin bottom-left
            MacImageIO.cgContextDrawImage(context, rect, strip);
          }
          finally {
            MacImageIO.cfRelease(strip);
          }
        }
        else {
          MacImageIO.setRect(rect, 0, 0, width, height);
          MacImageIO.cgContextDrawImage(context, rect, image);
        }
        int count = width * rows;
        MemorySegment.copy(buffer, JAVA_INT, 0, pixels, 0, count);
        if (alpha) unpremultiply(pixels, count);
        // setDataElements keeps the image "managed" (unlike wrapping our own array in a DataBuffer).
        raster.setDataElements(0, y0, width, rows, pixels);
      }
      return out;
    }

    /** Builds a CFDictionary from key/value pairs; values are {@link Boolean} or {@link Long}. Released on close. */
    private MemorySegment dictionary(Object... keyValues) {
      MemorySegment dictionary = own(MacImageIO.cfDictionaryCreateMutable(keyValues.length / 2));
      if (isNull(dictionary)) throw new IllegalStateException("CFDictionaryCreateMutable failed");
      for (int i = 0; i < keyValues.length; i += 2) {
        MemorySegment key = (MemorySegment) keyValues[i];
        Object value = keyValues[i + 1];
        if (value instanceof Boolean b) {
          MacImageIO.cfDictionarySetValue(dictionary, key, b ? MacImageIO.kCFBooleanTrue : MacImageIO.kCFBooleanFalse);
        }
        else if (value instanceof Long l) {
          MemorySegment number = MacImageIO.cfNumberCreateSInt64(arena, l);
          if (isNull(number)) throw new IllegalStateException("CFNumberCreate failed");
          try {
            MacImageIO.cfDictionarySetValue(dictionary, key, number); // the dictionary retains the number
          }
          finally {
            MacImageIO.cfRelease(number);
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
          MacImageIO.cfRelease(owned.pop());
        }
      }
      finally {
        try {
          MacImageIO.autoreleasePoolPop(pool);
        }
        finally {
          arena.close(); // after the bitmap context that points into the arena has been released
        }
      }
    }
  }

  /** Converts premultiplied 0xAARRGGBB to straight alpha in place. */
  static void unpremultiply(int[] pixels, int count) {
    for (int i = 0; i < count; i++) {
      int p = pixels[i];
      int a = p >>> 24;
      if (a == 255) continue;
      if (a == 0) {
        pixels[i] = 0;
        continue;
      }
      int half = a >> 1;
      int r = Math.min(255, (((p >> 16) & 0xFF) * 255 + half) / a);
      int g = Math.min(255, (((p >> 8) & 0xFF) * 255 + half) / a);
      int b = Math.min(255, ((p & 0xFF) * 255 + half) / a);
      pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
  }
}
