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
import java.util.ArrayDeque;

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
 * Thumbnails use a thumbnail embedded in the file ({@code thmb} reference) when one has the same orientation and aspect
 * ratio as the primary image and is at least as large as requested. Every native object is released in
 * {@code finally} blocks (images, handles, context, then the data). Thread-safe: every call has its own context.
 */
final class LibheifDecoder {
  /** An embedded thumbnail is used only if its aspect ratio differs by less than this from the primary image's. */
  private static final double THUMBNAIL_ASPECT_TOLERANCE = 0.02;

  private final Libheif lib;

  LibheifDecoder(@NotNull Libheif lib) {
    this.lib = lib;
  }

  @NotNull HeifImageInfo readInfo(byte[] data) throws IOException {
    try (Session session = new Session()) {
      session.open(data);
      return session.info(data);
    }
  }

  /**
   * @param maxPixelSize        0 for full size, else the maximum length of the longer side
   * @param useEmbeddedThumbnail whether a thumbnail stored in the file may be decoded instead of the primary image
   */
  @NotNull BufferedImage decode(byte[] data, int maxPixelSize, boolean useEmbeddedThumbnail) throws IOException {
    try (Session session = new Session()) {
      session.open(data);
      int width = lib.width(session.primary);
      int height = lib.height(session.primary);
      checkSize(width, height);
      boolean alpha = lib.hasAlpha(session.primary);
      long source = session.primary;
      if (useEmbeddedThumbnail && maxPixelSize > 0) {
        long thumbnail = session.thumbnail(width, height, maxPixelSize);
        if (thumbnail != 0) source = thumbnail;
      }
      return session.render(source, alpha, maxPixelSize);
    }
  }

  /** Name of the decoded source for tests: {@code "thumbnail WxH"} or {@code "primary"}. */
  @NotNull String thumbnailChoice(byte[] data, int maxPixelSize) throws IOException {
    try (Session session = new Session()) {
      session.open(data);
      long thumbnail = session.thumbnail(lib.width(session.primary), lib.height(session.primary), maxPixelSize);
      return thumbnail == 0 ? "primary" : "thumbnail " + lib.width(thumbnail) + "x" + lib.height(thumbnail);
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

  /** One decode: the native copy of the data, a context, handles and a decoded image, released in reverse order. */
  private final class Session implements AutoCloseable {
    private long memory;
    private long context;
    long primary;
    private final ArrayDeque<Long> handles = new ArrayDeque<>();
    private long image;

    void open(byte[] data) throws IOException {
      memory = Libheif.copyToNative(data);
      context = lib.contextAlloc();
      if (context == 0) throw new IOException("heif_context_alloc failed");
      lib.readFromMemoryWithoutCopy(context, memory, data.length);
      primary = own(lib.primaryImageHandle(context));
    }

    private long own(long handle) {
      handles.push(handle);
      return handle;
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

    /**
     * The smallest embedded thumbnail whose longer side is at least {@code min(maxPixelSize, longer side of the
     * image)} and whose orientation and aspect ratio match the primary image ({@code width x height}, transformed),
     * or 0.
     */
    long thumbnail(int width, int height, int maxPixelSize) throws IOException {
      int[] ids = lib.thumbnailIds(primary);
      if (ids.length == 0) return 0;
      int needed = Math.min(maxPixelSize, Math.max(width, height));
      double aspect = (double) width / height;
      long best = 0;
      int bestSide = Integer.MAX_VALUE;
      for (int id : ids) {
        long thumbnail;
        try {
          thumbnail = own(lib.thumbnail(primary, id));
        }
        catch (LibheifException e) {
          continue; // a broken thumbnail reference: use another one or the primary image
        }
        int w = lib.width(thumbnail), h = lib.height(thumbnail);
        if (w <= 0 || h <= 0) continue;
        int side = Math.max(w, h);
        boolean sameShape = Math.abs((double) w / h - aspect) <= THUMBNAIL_ASPECT_TOLERANCE * aspect;
        if (sameShape && side >= needed && side < bestSide) {
          best = thumbnail;
          bestSide = side;
        }
      }
      return best;
    }

    /** Decodes {@code handle} and converts it (see {@link PlaneConverter}); {@code alpha} is the result type's. */
    BufferedImage render(long handle, boolean alpha, int maxPixelSize) throws IOException {
      boolean decodeAlpha = handle == primary ? alpha : alpha && lib.hasAlpha(handle);
      image = lib.decode(handle, decodeAlpha);
      int width = lib.planeWidth(image);
      int height = lib.planeHeight(image);
      long[] stride = new long[1];
      long plane = lib.plane(image, stride);
      if (plane == 0) throw new IOException("The decoded image has no interleaved plane");
      if (width <= 0 || height <= 0) throw new IOException("Invalid decoded image size " + width + "x" + height);
      if (stride[0] <= 0 || stride[0] > Integer.MAX_VALUE / 2) throw new IOException("Invalid stride " + stride[0]);
      int rowBytes = (int) stride[0];
      ByteLayout layout = decodeAlpha ? ByteLayout.RGBA : ByteLayout.RGB;
      boolean premultiplied = decodeAlpha && lib.isPremultipliedAlpha(handle);
      BufferedImage result = PlaneConverter.convert(
        width, height, rowBytes, layout, premultiplied, alpha, maxPixelSize,
        (y0, rows, target) -> Libheif.read(plane + (long) y0 * rowBytes, target, rows * rowBytes));
      lib.releaseImage(image);
      image = 0;

      byte[] icc = lib.iccProfile(handle);
      if (icc == null && handle != primary) icc = lib.iccProfile(primary);
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
        while (!handles.isEmpty()) lib.release(handles.pop());
        lib.contextFree(context);
      }
      finally {
        Libheif.free(memory); // only after the context and every handle that may still read from it
      }
    }
  }
}
