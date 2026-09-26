package cn.yooss.heic.linux;

import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.PixelPipeline;
import cn.yooss.heic.backend.PixelPipeline.ByteLayout;
import cn.yooss.heic.backend.PlaneConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Decodes the primary image of HEIF data with libheif ({@link Libheif}):
 * <ol>
 *   <li>the data is copied into native memory that stays valid until the context is freed
 *   ({@code heif_context_read_from_memory_without_copy} keeps pointing into it);</li>
 *   <li>{@code heif_context_get_primary_image_handle}; the handle's size is the displayed size, because libheif applies
 *   the HEIF transformations ({@code irot}, {@code imir}, {@code clap}) while decoding (since libheif 1.0: the size
 *   is swapped for {@code irot} 90/270 and cropped for {@code clap} when the file is read). The EXIF orientation is
 *   not applied: a HEIF file expresses its orientation with {@code irot}/{@code imir}, and writers such as Apple's store
 *   the same orientation in both, so applying EXIF too would rotate twice (the fixtures cover both kinds);</li>
 *   <li>{@code heif_decode_image} to interleaved 8-bit RGB (RGBA with alpha) with the default options (transformations
 *   applied, images with more than 8 bits reduced to 8);</li>
 *   <li>the plane is read in strips and downscaled while reading ({@link PlaneConverter});</li>
 *   <li>an ICC profile ({@code colr} box {@code prof}/{@code rICC}) is converted to sRGB with the JDK's color
 *   management ({@link PixelPipeline#convertToSrgb}); libheif itself only converts YCbCr to RGB (with the
 *   {@code nclx} matrix coefficients) and leaves the colors in the file's color space.</li>
 * </ol>
 * An image above {@link #MAX_DECODE_SIDE} squared pixels is not decoded. Every native object is released in
 * {@code finally} blocks (image, handle, context, then the data). Thread-safe: every call has its own context.
 */
final class LibheifDecoder {
  /**
   * The largest image decoded: {@code MAX_DECODE_SIDE^2} pixels (libheif decodes at full resolution in native memory).
   * Checked on the declared size and by libheif itself when it builds the image ({@link Libheif#limitDecodeSize}),
   * which also covers a file that declares a smaller size.
   */
  static final int MAX_DECODE_SIDE = 16385;

  private final Libheif lib;
  private final int maxDecodeSide;

  LibheifDecoder(@NotNull Libheif lib) {
    this(lib, MAX_DECODE_SIDE);
  }

  /** @param maxDecodeSide the side of the largest (square) primary image that is decoded (tests make it small) */
  LibheifDecoder(@NotNull Libheif lib, int maxDecodeSide) {
    this.lib = lib;
    this.maxDecodeSide = maxDecodeSide;
  }

  @NotNull HeifImageInfo readInfo(byte[] data) throws IOException {
    try (Session session = new Session()) {
      session.open(data);
      return session.info(data);
    }
  }

  /** @param maxPixelSize 0 for full size, else the maximum length of the longer side */
  @NotNull BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
    try (Session session = new Session()) {
      session.open(data);
      int width = lib.width(session.primary);
      int height = lib.height(session.primary);
      checkSize(width, height);
      long limit = (long) maxDecodeSide * maxDecodeSide;
      if ((long) width * height > limit) {
        throw new IOException(String.format(Locale.ROOT, "The image is too large to decode with libheif: %dx%d (%.0f "
                                                         + "megapixels, at most %.0f)",
                                            width, height, width * (double) height / 1e6, limit / 1e6));
      }
      lib.limitDecodeSize(session.context, maxDecodeSide); // the size libheif builds (e.g. a grid's canvas)
      return session.render(lib.hasAlpha(session.primary), maxPixelSize);
    }
  }

  private static void checkSize(int width, int height) throws IOException {
    if (width <= 0 || height <= 0) throw new IOException("Invalid image size " + width + "x" + height);
  }

  /** Major brand of the {@code ftyp} box (e.g. {@code heic}, {@code mif1}), used as the type identifier. */
  static @Nullable String majorBrand(byte[] data) {
    if (data.length < 12) return null;
    return new String(data, 8, 4, StandardCharsets.ISO_8859_1).trim();
  }

  /** One decode: the native copy of the data, a context, the primary image handle and a decoded image. */
  private final class Session implements AutoCloseable {
    private long memory;
    private long context;
    long primary;
    private long image;

    void open(byte[] data) throws IOException {
      memory = Libheif.copyToNative(data);
      context = lib.contextAlloc();
      if (context == 0) throw new IOException("heif_context_alloc failed");
      lib.readFromMemoryWithoutCopy(context, memory, data.length);
      primary = lib.primaryImageHandle(context);
    }

    HeifImageInfo info(byte[] data) throws IOException {
      int width = lib.width(primary);
      int height = lib.height(primary);
      checkSize(width, height);
      int count = Math.max(1, lib.numberOfTopLevelImages(context));
      int index = lib.primaryImageIndex(context, count);
      // Transformations are applied by libheif: the stored size is the displayed size, orientation 1.
      return new HeifImageInfo(majorBrand(data), count, index, width, height, 1, lib.lumaBitsPerPixel(primary),
                               lib.hasAlpha(primary));
    }

    /** Decodes the primary image and converts it (see {@link PlaneConverter}). */
    BufferedImage render(boolean alpha, int maxPixelSize) throws IOException {
      image = lib.decode(primary, alpha);
      int width = lib.planeWidth(image);
      int height = lib.planeHeight(image);
      long[] stride = new long[1];
      long plane = lib.plane(image, stride);
      if (plane == 0) throw new IOException("The decoded image has no interleaved plane");
      if (width <= 0 || height <= 0) throw new IOException("Invalid decoded image size " + width + "x" + height);
      if (stride[0] <= 0 || stride[0] > Integer.MAX_VALUE / 2) throw new IOException("Invalid stride " + stride[0]);
      int rowBytes = (int) stride[0];
      ByteLayout layout = alpha ? ByteLayout.RGBA : ByteLayout.RGB;
      boolean premultiplied = alpha && lib.isPremultipliedAlpha(primary);
      BufferedImage result = PlaneConverter.convert(
        width, height, rowBytes, layout, premultiplied, alpha, maxPixelSize,
        (y0, rows, target) -> Libheif.read(plane + (long) y0 * rowBytes, target, rows * rowBytes));
      lib.releaseImage(image);
      image = 0;

      byte[] icc = lib.iccProfile(primary);
      if (icc != null) {
        try {
          PixelPipeline.convertToSrgb(result, icc);
        }
        catch (IOException | RuntimeException e) {
          // Not an RGB profile (e.g. a gray profile of a monochrome image) or a broken one: keep the file's colors.
        }
      }
      return result;
    }

    @Override
    public void close() {
      try {
        lib.releaseImage(image);
        lib.release(primary);
        lib.contextFree(context);
      }
      finally {
        Libheif.free(memory); // after the context and the handle, which read from it
      }
    }
  }
}
