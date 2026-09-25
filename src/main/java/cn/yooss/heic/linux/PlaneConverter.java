package cn.yooss.heic.linux;

import cn.yooss.heic.backend.PixelPipeline;
import cn.yooss.heic.backend.PixelPipeline.ByteLayout;
import org.jetbrains.annotations.NotNull;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

/**
 * Turns an 8-bit interleaved RGB or RGBA plane (libheif's decoded image, read row by row from native memory) into the
 * backend's {@link BufferedImage}, downscaled to at most {@code maxPixelSize} on the longer side without ever holding
 * the full-size image in the Java heap. libheif always decodes at full resolution, so downscaling happens here:
 * <ol>
 *   <li>an integer box filter (area average, alpha-weighted) reduces the plane by the largest factor {@code k} that
 *   keeps the result at least as large as the target, while the rows are streamed in strips;</li>
 *   <li>one bilinear step (less than a factor of two, so no aliasing) scales that to the exact target size.</li>
 * </ol>
 * Pure Java (tests feed it byte arrays).
 */
final class PlaneConverter {
  /** Bytes of plane rows read per strip (bounds the scratch buffer). */
  static final int STRIP_BYTES = 4 << 20;

  /** Reads rows of the plane. */
  interface Rows {
    /** Copies rows {@code y0 .. y0+rows-1} ({@code stride} bytes each) into {@code target} from index 0. */
    void read(int y0, int rows, byte[] target) throws IOException;
  }

  private PlaneConverter() {
  }

  /**
   * @param width          plane width in pixels
   * @param height         plane height in pixels
   * @param stride         bytes per plane row ({@code >= width * layout.bytesPerPixel()})
   * @param layout         {@link ByteLayout#RGB} or {@link ByteLayout#RGBA}
   * @param premultiplied  whether RGBA colors are premultiplied by alpha
   * @param alpha          whether the result is {@code TYPE_INT_ARGB} (else {@code TYPE_INT_RGB}, alpha dropped)
   * @param maxPixelSize   0 for full size, else the maximum length of the longer side of the result
   */
  static @NotNull BufferedImage convert(int width, int height, int stride, @NotNull ByteLayout layout, boolean premultiplied,
                                        boolean alpha, int maxPixelSize, @NotNull Rows rows) throws IOException {
    if (width <= 0 || height <= 0) throw new IOException("Invalid decoded image size " + width + "x" + height);
    if (stride < (long) width * layout.bytesPerPixel()) {
      throw new IOException("Invalid stride " + stride + " for " + width + " pixels of " + layout);
    }
    int longest = Math.max(width, height);
    if (maxPixelSize <= 0 || longest <= maxPixelSize) {
      return copy(width, height, stride, layout, premultiplied, alpha, rows);
    }
    int targetW = targetSide(width, height, maxPixelSize);
    int targetH = targetSide(height, width, maxPixelSize);
    int factor = Math.max(1, Math.min(width / targetW, height / targetH));
    BufferedImage reduced = factor == 1
                            ? copy(width, height, stride, layout, premultiplied, alpha, rows)
                            : boxReduce(width, height, stride, layout, premultiplied, alpha, factor, rows);
    return scale(reduced, targetW, targetH);
  }

  /** The side of the result for a {@code side x other} image whose longer side becomes {@code maxPixelSize}. */
  static int targetSide(int side, int other, int maxPixelSize) {
    if (side >= other) return Math.min(side, maxPixelSize);
    double scale = (double) maxPixelSize / other;
    return (int) Math.max(1, Math.min(maxPixelSize, Math.round(side * scale)));
  }

  private static int stripRows(int height, int stride) {
    return Math.max(1, Math.min(height, STRIP_BYTES / Math.max(1, stride)));
  }

  /** Full size: strips of rows through {@link PixelPipeline#writeByteRows}. */
  private static BufferedImage copy(int width, int height, int stride, ByteLayout layout, boolean premultiplied,
                                    boolean alpha, Rows rows) throws IOException {
    BufferedImage image = PixelPipeline.newImage(width, height, alpha);
    int stripRows = stripRows(height, stride);
    byte[] strip = new byte[stripRows * stride];
    for (int y0 = 0; y0 < height; y0 += stripRows) {
      int n = Math.min(stripRows, height - y0);
      rows.read(y0, n, strip);
      PixelPipeline.writeByteRows(image, y0, n, strip, 0, stride, layout, premultiplied);
    }
    return image;
  }

  /**
   * Averages {@code factor x factor} blocks (the last row and column of blocks may be smaller), weighting the colors by
   * alpha so that transparent pixels do not darken their neighbors. The result has straight alpha.
   */
  private static BufferedImage boxReduce(int width, int height, int stride, ByteLayout layout, boolean premultiplied,
                                         boolean alpha, int factor, Rows rows) throws IOException {
    int outW = (width + factor - 1) / factor;
    int outH = (height + factor - 1) / factor;
    BufferedImage out = PixelPipeline.newImage(outW, outH, alpha);
    int bpp = layout.bytesPerPixel();
    boolean sourceAlpha = layout.hasAlpha();
    long[] sumA = new long[outW], sumR = new long[outW], sumG = new long[outW], sumB = new long[outW];
    int[] outRow = new int[outW];

    int stripRows = stripRows(height, stride);
    byte[] strip = new byte[stripRows * stride];
    int blockRows = 0;
    for (int y0 = 0; y0 < height; y0 += stripRows) {
      int n = Math.min(stripRows, height - y0);
      rows.read(y0, n, strip);
      for (int r = 0; r < n; r++) {
        int in = r * stride;
        for (int ox = 0, x = 0; ox < outW; ox++) {
          int end = Math.min(width, x + factor);
          long a = 0, red = 0, green = 0, blue = 0;
          for (; x < end; x++, in += bpp) {
            if (sourceAlpha) {
              int pixelAlpha = strip[in + 3] & 0xFF;
              int weight = premultiplied ? 255 : pixelAlpha; // premultiplied colors are already weighted by alpha
              a += pixelAlpha;
              red += (strip[in] & 0xFF) * weight;
              green += (strip[in + 1] & 0xFF) * weight;
              blue += (strip[in + 2] & 0xFF) * weight;
            }
            else {
              red += strip[in] & 0xFF;
              green += strip[in + 1] & 0xFF;
              blue += strip[in + 2] & 0xFF;
            }
          }
          sumA[ox] += a;
          sumR[ox] += red;
          sumG[ox] += green;
          sumB[ox] += blue;
        }
        blockRows++;
        int y = y0 + r;
        if (blockRows == factor || y == height - 1) {
          for (int ox = 0; ox < outW; ox++) {
            int blockCols = Math.min(factor, width - ox * factor);
            long count = (long) blockRows * blockCols;
            outRow[ox] = sourceAlpha ? averageWithAlpha(sumA[ox], sumR[ox], sumG[ox], sumB[ox], count)
                                     : 0xFF000000 | average(sumR[ox], count) << 16 | average(sumG[ox], count) << 8
                                       | average(sumB[ox], count);
          }
          out.getRaster().setDataElements(0, y / factor, outW, 1, outRow);
          Arrays.fill(sumA, 0);
          Arrays.fill(sumR, 0);
          Arrays.fill(sumG, 0);
          Arrays.fill(sumB, 0);
          blockRows = 0;
        }
      }
    }
    return out;
  }

  private static int average(long sum, long count) {
    return (int) Math.min(255, (sum + count / 2) / count);
  }

  /** Straight-alpha {@code 0xAARRGGBB} from alpha-weighted sums (colors weighted by alpha, or by 255 if premultiplied). */
  private static int averageWithAlpha(long sumA, long sumR, long sumG, long sumB, long count) {
    if (sumA == 0) return 0;
    int a = average(sumA, count);
    if (a == 0) return 0;
    int r = (int) Math.min(255, (sumR + sumA / 2) / sumA);
    int g = (int) Math.min(255, (sumG + sumA / 2) / sumA);
    int b = (int) Math.min(255, (sumB + sumA / 2) / sumA);
    return a << 24 | r << 16 | g << 8 | b;
  }

  /** Bilinear scaling to exactly {@code width x height} (a factor below 2 after {@link #boxReduce}). */
  private static BufferedImage scale(BufferedImage image, int width, int height) {
    if (image.getWidth() == width && image.getHeight() == height) return image;
    BufferedImage result = new BufferedImage(width, height, image.getType());
    Graphics2D g = result.createGraphics();
    try {
      g.setComposite(AlphaComposite.Src);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
      g.drawImage(image, 0, 0, width, height, null);
    }
    finally {
      g.dispose();
    }
    return result;
  }
}
