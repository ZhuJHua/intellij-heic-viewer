package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.color.ProfileDataException;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.Locale;

/**
 * Shared, pure-Java pixel handling for the backends: native pixel buffers to {@link BufferedImage}s in strips,
 * un-premultiplication, the EXIF/HEIF orientation, downscaling and ICC profile to sRGB conversion. Every backend
 * produces the same kind of image ({@link HeifBackend}): {@code TYPE_INT_RGB} when opaque, non-premultiplied
 * {@code TYPE_INT_ARGB} with alpha, 8-bit sRGB, orientation applied.
 * <p>
 * Typical use by a backend whose decoder hands out an 8-bit buffer:
 * <pre>{@code
 * BufferedImage image = PixelPipeline.newImage(width, height, hasAlpha);
 * int stripRows = PixelPipeline.stripRows(width, height, PixelPipeline.STRIP_PIXELS);
 * byte[] strip = new byte[stride * stripRows];
 * for (int y0 = 0; y0 < height; y0 += stripRows) {
 *   int rows = Math.min(stripRows, height - y0);
 *   // copy rows y0 .. y0+rows-1 of the native buffer into strip (e.g. Pointer.read)
 *   PixelPipeline.writeByteRows(image, y0, rows, strip, 0, stride, ByteLayout.BGRA, premultiplied);
 * }
 * if (iccProfile != null) PixelPipeline.convertToSrgb(image, iccProfile);
 * image = PixelPipeline.applyOrientation(image, orientation);   // unless the decoder applied it
 * image = PixelPipeline.downscale(image, maxPixelSize);         // unless the decoder scaled already
 * }</pre>
 * All images are written with {@code WritableRaster.setDataElements}, which keeps them "managed" (hardware
 * accelerated when painted), unlike images built around an {@code int[]} of their own.
 */
public final class PixelPipeline {
  /** Pixels per strip (bounds scratch buffers to 4 MB). */
  public static final int STRIP_PIXELS = 1 << 20;
  /** A {@code TYPE_INT_*} {@link BufferedImage} is backed by a single {@code int[]}. */
  public static final long MAX_IMAGE_PIXELS = Integer.MAX_VALUE - 16;

  /** Byte order of an 8-bit-per-channel native pixel buffer. */
  public enum ByteLayout {
    RGB(3, -1, 0, 1, 2),
    BGR(3, -1, 2, 1, 0),
    RGBA(4, 3, 0, 1, 2),
    BGRA(4, 3, 2, 1, 0),
    ARGB(4, 0, 1, 2, 3),
    /** 4 bytes per pixel, the 4th is ignored (opaque). */
    RGBX(4, -1, 0, 1, 2),
    /** 4 bytes per pixel, the 4th is ignored (opaque); on little-endian machines the int layout of TYPE_INT_RGB. */
    BGRX(4, -1, 2, 1, 0);

    private final int bytesPerPixel;
    private final int alpha, red, green, blue;

    ByteLayout(int bytesPerPixel, int alpha, int red, int green, int blue) {
      this.bytesPerPixel = bytesPerPixel;
      this.alpha = alpha;
      this.red = red;
      this.green = green;
      this.blue = blue;
    }

    public int bytesPerPixel() {
      return bytesPerPixel;
    }

    public boolean hasAlpha() {
      return alpha >= 0;
    }
  }

  private PixelPipeline() {
  }

  /**
   * A new {@code TYPE_INT_ARGB} (with alpha) or {@code TYPE_INT_RGB} image.
   *
   * @throws IOException if the size is not positive or too large for a single {@code int[]}
   */
  public static @NotNull BufferedImage newImage(long width, long height, boolean alpha) throws IOException {
    if (width <= 0 || height <= 0 || width * height > MAX_IMAGE_PIXELS) {
      throw new IOException(String.format(Locale.ROOT, "Decoded image size %dx%d is not supported", width, height));
    }
    return new BufferedImage((int) width, (int) height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
  }

  /** Rows per strip for an image of {@code width} pixels: at least 1, at most {@code height}. */
  public static int stripRows(int width, int height, int stripPixels) {
    return Math.max(1, Math.min(height, stripPixels / Math.max(1, width)));
  }

  /**
   * The size of a {@code width x height} image decoded with {@code maxPixelSize}: the image itself when it fits (or for
   * {@code 0}), otherwise the longer side becomes {@code maxPixelSize} and the other side keeps the aspect ratio
   * (rounded, at least 1). The rule of {@link #downscale}, {@link PlaneConverter} and the backends' scalers.
   */
  public static int[] targetSize(int width, int height, int maxPixelSize) {
    int longest = Math.max(width, height);
    if (maxPixelSize <= 0 || longest <= maxPixelSize) return new int[]{width, height};
    double scale = (double) maxPixelSize / longest;
    int targetWidth = width >= height ? maxPixelSize : (int) Math.max(1, Math.min(maxPixelSize, Math.round(width * scale)));
    int targetHeight = height > width ? maxPixelSize : (int) Math.max(1, Math.min(maxPixelSize, Math.round(height * scale)));
    return new int[]{targetWidth, targetHeight};
  }

  /**
   * Writes {@code rows} full rows of {@code 0xAARRGGBB} pixels (row-major, {@code target.getWidth()} per row) at row
   * {@code y0}. With {@code premultiplied}, the pixels are first converted to straight alpha, in place, if the target
   * has alpha. For an opaque target the alpha byte is ignored.
   */
  public static void writeArgbRows(@NotNull BufferedImage target, int y0, int rows, int[] pixels,
                                   boolean premultiplied) {
    int width = target.getWidth();
    int count = width * rows;
    if (pixels.length < count) throw new IllegalArgumentException("Need " + count + " pixels, got " + pixels.length);
    if (premultiplied && target.getColorModel().hasAlpha()) unpremultiply(pixels, count);
    target.getRaster().setDataElements(0, y0, width, rows, pixels);
  }

  /**
   * Converts {@code rows} rows of an 8-bit buffer (starting at {@code offset}, {@code stride} bytes per row) and
   * writes them at row {@code y0} of {@code target} (see {@link #writeArgbRows}).
   */
  public static void writeByteRows(@NotNull BufferedImage target, int y0, int rows, byte[] source, int offset,
                                   int stride, @NotNull ByteLayout layout, boolean premultiplied) {
    writeByteRows(target, y0, rows, source, offset, stride, layout, premultiplied, null);
  }

  /**
   * {@link #writeByteRows(BufferedImage, int, int, byte[], int, int, ByteLayout, boolean)} with a scratch array for the
   * converted pixels, reused when it holds at least {@code target.getWidth() * rows} pixels ({@code null}: a new one), so
   * that a strip loop allocates it once.
   *
   * @return the scratch array used (pass it to the next call)
   */
  public static int[] writeByteRows(@NotNull BufferedImage target, int y0, int rows, byte[] source, int offset,
                                    int stride, @NotNull ByteLayout layout, boolean premultiplied, int[] scratch) {
    int width = target.getWidth();
    int bpp = layout.bytesPerPixel;
    if (stride < width * bpp) throw new IllegalArgumentException("stride " + stride + " < " + width + " * " + bpp);
    int[] pixels = scratch != null && scratch.length >= width * rows ? scratch : new int[width * rows];
    int a = layout.alpha, r = layout.red, g = layout.green, b = layout.blue;
    for (int row = 0; row < rows; row++) {
      int in = offset + row * stride;
      int out = row * width;
      for (int x = 0; x < width; x++, in += bpp) {
        int alpha = a >= 0 ? source[in + a] & 0xFF : 0xFF;
        pixels[out + x] = alpha << 24 | (source[in + r] & 0xFF) << 16 | (source[in + g] & 0xFF) << 8 | source[in + b] & 0xFF;
      }
    }
    writeArgbRows(target, y0, rows, pixels, premultiplied && layout.hasAlpha());
    return pixels;
  }

  /** Converts premultiplied {@code 0xAARRGGBB} to straight alpha in place (rounded, clamped to 255). */
  public static void unpremultiply(int[] pixels, int count) {
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

  /**
   * Applies the EXIF-style {@code orientation} (1..8; HEIF {@code irot}/{@code imir} map onto the same values) to
   * the stored image and returns the displayed image: {@code image} itself for 1 (or an invalid value), otherwise a new
   * image of the same type; orientations 5..8 swap width and height. The pixels are remapped exactly (no filtering).
   * {@code image} must not be used afterwards (its pixel array may have been accessed directly).
   */
  public static @NotNull BufferedImage applyOrientation(@NotNull BufferedImage image, int orientation) {
    if (orientation < 2 || orientation > 8) return image;
    BufferedImage source = intImage(image);
    int w = source.getWidth(), h = source.getHeight();
    int[] src = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
    boolean swap = HeifImageInfo.swapsAxes(orientation);
    int dw = swap ? h : w, dh = swap ? w : h;
    BufferedImage out = new BufferedImage(dw, dh, source.getType());
    int stripRows = stripRows(dw, dh, STRIP_PIXELS);
    int[] strip = new int[dw * stripRows];
    for (int y0 = 0; y0 < dh; y0 += stripRows) {
      int rows = Math.min(stripRows, dh - y0);
      for (int row = 0; row < rows; row++) {
        int dy = y0 + row;
        int o = row * dw;
        switch (orientation) {
          case 2: // mirror horizontally
            for (int dx = 0, s = dy * w + w - 1; dx < dw; dx++, s--) strip[o + dx] = src[s];
            break;
          case 3: // rotate 180
            for (int dx = 0, s = (h - 1 - dy) * w + w - 1; dx < dw; dx++, s--) strip[o + dx] = src[s];
            break;
          case 4: // mirror vertically
            System.arraycopy(src, (h - 1 - dy) * w, strip, o, dw);
            break;
          case 5: // transpose: (dy, dx)
            for (int dx = 0, s = dy; dx < dw; dx++, s += w) strip[o + dx] = src[s];
            break;
          case 6: // rotate 90 clockwise: (dy, h-1-dx)
            for (int dx = 0, s = (h - 1) * w + dy; dx < dw; dx++, s -= w) strip[o + dx] = src[s];
            break;
          case 7: // transverse: (w-1-dy, h-1-dx)
            for (int dx = 0, s = (h - 1) * w + (w - 1 - dy); dx < dw; dx++, s -= w) strip[o + dx] = src[s];
            break;
          default: // 8, rotate 90 counterclockwise: (w-1-dy, dx)
            for (int dx = 0, s = w - 1 - dy; dx < dw; dx++, s += w) strip[o + dx] = src[s];
            break;
        }
      }
      out.getRaster().setDataElements(0, y0, dw, rows, strip);
    }
    return out;
  }

  /**
   * {@code image} scaled down so that its longer side is {@code maxPixelSize} (aspect ratio preserved, each side at
   * least 1 pixel), or {@code image} itself if {@code maxPixelSize} is {@code 0} or the image already fits. Bilinear
   * halving steps followed by one bilinear step, so every source pixel contributes (no aliasing); the type is kept.
   */
  public static @NotNull BufferedImage downscale(@NotNull BufferedImage image, int maxPixelSize) {
    int w = image.getWidth(), h = image.getHeight();
    int longest = Math.max(w, h);
    if (maxPixelSize <= 0 || longest <= maxPixelSize) return image;
    double scale = (double) maxPixelSize / longest;
    int targetW = w >= h ? maxPixelSize : (int) Math.max(1, Math.min(maxPixelSize, Math.round(w * scale)));
    int targetH = h > w ? maxPixelSize : (int) Math.max(1, Math.min(maxPixelSize, Math.round(h * scale)));
    int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    BufferedImage current = image;
    int cw = w, ch = h;
    while (cw >= 2 * targetW && ch >= 2 * targetH) {
      cw /= 2;
      ch /= 2;
      current = draw(current, cw, ch, type);
    }
    if (cw != targetW || ch != targetH || current == image) current = draw(current, targetW, targetH, type);
    return current;
  }

  private static BufferedImage draw(BufferedImage source, int width, int height, int type) {
    BufferedImage result = new BufferedImage(width, height, type);
    Graphics2D g = result.createGraphics();
    try {
      g.setComposite(AlphaComposite.Src);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
      g.drawImage(source, 0, 0, width, height, null);
    }
    finally {
      g.dispose();
    }
    return result;
  }

  /**
   * Converts the colors of {@code image} ({@code TYPE_INT_RGB} or {@code TYPE_INT_ARGB} whose values are in the color
   * space of {@code iccProfile}, e.g. Display P3 from a HEIF {@code colr} box) to sRGB, in place and in strips (alpha
   * is kept). Uses the JDK's color management ({@link ColorConvertOp}, LittleCMS).
   *
   * @throws IOException if the profile cannot be parsed or is not an RGB profile
   */
  public static void convertToSrgb(@NotNull BufferedImage image, byte[] iccProfile) throws IOException {
    ICC_ColorSpace space;
    try {
      space = new ICC_ColorSpace(ICC_Profile.getInstance(iccProfile));
    }
    catch (IllegalArgumentException | ProfileDataException e) {
      throw new IOException("Invalid ICC profile: " + e.getMessage(), e);
    }
    if (space.getType() != ColorSpace.TYPE_RGB) throw new IOException("Not an RGB ICC profile (color space type " + space.getType() + ")");

    if (image.getType() != BufferedImage.TYPE_INT_RGB && image.getType() != BufferedImage.TYPE_INT_ARGB) {
      throw new IllegalArgumentException("Expected TYPE_INT_RGB or TYPE_INT_ARGB, got type " + image.getType());
    }
    boolean alpha = image.getColorModel().hasAlpha();
    // The same pixels, interpreted in the profile's color space.
    DirectColorModel profileModel = new DirectColorModel(space, alpha ? 32 : 24, 0x00FF0000, 0x0000FF00, 0x000000FF,
                                                         alpha ? 0xFF000000 : 0, false, DataBuffer.TYPE_INT);
    BufferedImage inProfile = new BufferedImage(profileModel, image.getRaster(), false, null);
    ColorConvertOp convert = new ColorConvertOp(null);
    int width = image.getWidth(), height = image.getHeight();
    int stripRows = stripRows(width, height, STRIP_PIXELS);
    BufferedImage strip = new BufferedImage(width, stripRows, image.getType());
    WritableRaster raster = image.getRaster();
    for (int y0 = 0; y0 < height; y0 += stripRows) {
      int rows = Math.min(stripRows, height - y0);
      BufferedImage source = inProfile.getSubimage(0, y0, width, rows);
      BufferedImage destination = rows == stripRows ? strip : strip.getSubimage(0, 0, width, rows);
      convert.filter(source, destination);
      raster.setDataElements(0, y0, destination.getRaster());
    }
  }

  /**
   * {@code image} if it is a plain {@code TYPE_INT_RGB}/{@code TYPE_INT_ARGB} image whose pixel array holds exactly
   * its pixels (row-major, no padding or offset), otherwise a copy of that kind.
   */
  private static BufferedImage intImage(BufferedImage image) {
    int type = image.getType();
    if ((type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB)
        && image.getRaster().getDataBuffer() instanceof DataBufferInt
        && image.getRaster().getDataBuffer().getNumBanks() == 1
        && image.getSampleModel() instanceof SinglePixelPackedSampleModel
        && ((SinglePixelPackedSampleModel) image.getSampleModel()).getScanlineStride() == image.getWidth()
        && image.getRaster().getSampleModelTranslateX() == 0 && image.getRaster().getSampleModelTranslateY() == 0
        && image.getRaster().getDataBuffer().getOffset() == 0) {
      return image;
    }
    BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(),
                                           image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    Graphics2D g = copy.createGraphics();
    try {
      g.setComposite(AlphaComposite.Src);
      g.drawImage(image, 0, 0, null);
    }
    finally {
      g.dispose();
    }
    return copy;
  }
}
