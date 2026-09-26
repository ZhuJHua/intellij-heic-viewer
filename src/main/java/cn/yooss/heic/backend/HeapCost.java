package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;

/**
 * Java heap estimates of decodes, for {@link HeifBackend#decodeHeapBytes} (the image reader's heap safety valve decides
 * the decode size with them before decoding). Only the Java heap counts: the native memory of the system decoders
 * (ImageIO.framework's decoded image, WIC's bitmap, libheif's plane) is not part of it.
 * <p>
 * Measured on macOS 26 (M4 Pro, JBR 25, G1) with a 48-megapixel (8064 x 6048) HEIC decoded at full size: the smallest
 * {@code -Xmx} that decodes it is 195 MB through ImageIO.framework and 202 MB through libheif 1.23, for a 186 MB result
 * (a JVM that decodes a 600 x 400 image needs 7 MB): the result plus 9 and 16 MB of strips and scratch arrays.
 * {@link #FIXED_BYTES} covers these with room to spare; {@code HeifBackendContractTest} checks on every OS that a decode
 * allocates no more than the estimate.
 */
public final class HeapCost {
  /**
   * Heap a decode needs besides its images, independent of the image size: the strips (at most
   * {@link PixelPipeline#STRIP_PIXELS} pixels, {@code PlaneConverter.STRIP_BYTES} bytes), the scratch arrays of the
   * pixel conversion and of the ICC profile conversion, and small objects.
   */
  public static final long FIXED_BYTES = 24L << 20;

  private HeapCost() {
  }

  /** Bytes of a {@code TYPE_INT_RGB}/{@code TYPE_INT_ARGB} image (one {@code int} per pixel). */
  public static long image(long width, long height) {
    return 4 * width * height;
  }

  /** Bytes of the result of {@code decode(data, maxPixelSize)}: the display size, scaled to {@code maxPixelSize}. */
  public static long result(@NotNull HeifImageInfo info, int maxPixelSize) {
    int[] size = PixelPipeline.targetSize(info.width(), info.height(), maxPixelSize);
    return image(size[0], size[1]);
  }

  /** Whether a decode with {@code maxPixelSize} is smaller than the image. */
  public static boolean isScaled(@NotNull HeifImageInfo info, int maxPixelSize) {
    return maxPixelSize > 0 && Math.max(info.width(), info.height()) > maxPixelSize;
  }

  /**
   * The intermediate image of {@link PlaneConverter} when it scales a {@code width x height} plane to
   * {@code maxPixelSize}: the plane reduced by the largest integer factor that keeps it at least as large as the target
   * (the plane itself, copied, for a factor of 1); alive together with the result while the last bilinear step runs.
   * {@code 0} if the plane is not scaled.
   */
  public static long planeReduction(long width, long height, int maxPixelSize) {
    if (maxPixelSize <= 0 || Math.max(width, height) <= maxPixelSize || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
      return 0;
    }
    int w = (int) width, h = (int) height;
    int targetWidth = PlaneConverter.targetSide(w, h, maxPixelSize);
    int targetHeight = PlaneConverter.targetSide(h, w, maxPixelSize);
    long factor = Math.max(1, Math.min(width / targetWidth, height / targetHeight));
    return image((width + factor - 1) / factor, (height + factor - 1) / factor);
  }

  /**
   * The heap an image of {@code info} decoded with {@code maxPixelSize} needs when the IDE first paints it: the image and
   * a copy of it. Java2D converts a {@code TYPE_INT_*} image into a temporary copy of the drawn area the first time it
   * draws it scaled (before it caches it as a texture): measured with JBR 17 and 25 on macOS, Metal and OpenGL, the first
   * bilinear paint of a 48 MP image allocated 205 MB, of a 208 MP image 814 MB (+19 MB each), later paints nothing. In
   * IntelliJ IDEA 2024.1.7 on a 2 GB heap, the first paint of a 208 MP HEIC image ran out of heap in exactly that copy
   * ({@code DrawImage.makeBufferedImage}). The same holds for a PNG in the IDE's viewer; the image reader counts it so
   * that the heap safety valve leaves room for it.
   */
  public static long painted(@NotNull HeifImageInfo info, int maxPixelSize) {
    return 2 * result(info, maxPixelSize) + FIXED_BYTES;
  }

  /** The default of {@link HeifBackend#decodeHeapBytes}: the result and {@link #FIXED_BYTES}. */
  public static long simple(@NotNull HeifImageInfo info, int maxPixelSize) {
    return result(info, maxPixelSize) + FIXED_BYTES;
  }
}
