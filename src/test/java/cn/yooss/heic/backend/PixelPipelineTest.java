package cn.yooss.heic.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Java, runs on every OS: the shared pixel handling of all backends. */
class PixelPipelineTest {

  @Test
  void newImageTypes() throws IOException {
    assertEquals(BufferedImage.TYPE_INT_RGB, PixelPipeline.newImage(3, 2, false).getType());
    BufferedImage alpha = PixelPipeline.newImage(3, 2, true);
    assertEquals(BufferedImage.TYPE_INT_ARGB, alpha.getType());
    assertFalse(alpha.isAlphaPremultiplied());
    assertThrows(IOException.class, () -> PixelPipeline.newImage(0, 2, false));
    assertThrows(IOException.class, () -> PixelPipeline.newImage(2, -1, false));
    assertThrows(IOException.class, () -> PixelPipeline.newImage(1L << 16, 1L << 16, false), "more than one int[]");
  }

  @Test
  void stripRows() {
    assertEquals(524, PixelPipeline.stripRows(2000, 1200, PixelPipeline.STRIP_PIXELS));
    assertEquals(1200, PixelPipeline.stripRows(100, 1200, PixelPipeline.STRIP_PIXELS), "never more than the height");
    assertEquals(1, PixelPipeline.stripRows(3_000_000, 10, PixelPipeline.STRIP_PIXELS), "at least one row");
    assertEquals(1, PixelPipeline.stripRows(0, 0, 1));
  }

  @Test
  void unpremultiply() {
    int[] pixels = {0xFFFF0000, 0x00FFFFFF, 0x80800000, 0x40004000, 0x01010101};
    PixelPipeline.unpremultiply(pixels, pixels.length);
    assertArrayEquals(new int[]{0xFFFF0000, 0x00000000, 0x80FF0000, 0x4000FF00, 0x01FFFFFF}, pixels);
  }

  @Test
  void unpremultiplyOnlyTouchesTheGivenCount() {
    int[] pixels = {0x80800000, 0x80800000};
    PixelPipeline.unpremultiply(pixels, 1);
    assertArrayEquals(new int[]{0x80FF0000, 0x80800000}, pixels);
  }

  @Test
  void writeArgbRowsUnpremultipliesOnlyImagesWithAlpha() throws IOException {
    BufferedImage alpha = PixelPipeline.newImage(2, 2, true);
    PixelPipeline.writeArgbRows(alpha, 1, 1, new int[]{0x80800000, 0xFF00FF00}, true);
    assertEquals(0x80FF0000, alpha.getRGB(0, 1));
    assertEquals(0xFF00FF00, alpha.getRGB(1, 1));
    assertEquals(0, alpha.getRGB(0, 0), "rows outside the strip are untouched");

    BufferedImage opaque = PixelPipeline.newImage(2, 1, false);
    PixelPipeline.writeArgbRows(opaque, 0, 1, new int[]{0x80800000, 0x00123456}, true);
    assertEquals(0xFF800000, opaque.getRGB(0, 0), "alpha is ignored for opaque images");
    assertEquals(0xFF123456, opaque.getRGB(1, 0));

    assertThrows(IllegalArgumentException.class, () -> PixelPipeline.writeArgbRows(opaque, 0, 1, new int[1], false));
  }

  @ParameterizedTest
  @CsvSource({
      "RGB,  false", "BGR,  false", "RGBA, false", "BGRA, false", "ARGB, false", "RGBX, false", "BGRX, false",
      "RGBA, true",  "BGRA, true",  "ARGB, true",
  })
  void writeByteRowsForEveryLayout(PixelPipeline.ByteLayout layout, boolean premultiplied) throws IOException {
    // Two rows of two pixels with padding at the end of each row (stride > width * bpp).
    int[] argb = {0xFF102030, 0x80402000, 0x00000000, 0xC0306090};
    int bpp = layout.bytesPerPixel();
    int stride = 2 * bpp + 3;
    byte[] buffer = new byte[5 + 2 * stride];
    for (int i = 0; i < argb.length; i++) {
      int p = argb[i];
      int a = p >>> 24;
      int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
      if (premultiplied) {
        r = r * a / 255;
        g = g * a / 255;
        b = b * a / 255;
      }
      int at = 5 + (i / 2) * stride + (i % 2) * bpp;
      String order = layout.name();
      for (int c = 0; c < bpp; c++) {
        char channel = order.charAt(c);
        int value = channel == 'R' ? r : channel == 'G' ? g : channel == 'B' ? b : channel == 'A' ? a : 0x5A;
        buffer[at + c] = (byte) value;
      }
    }
    BufferedImage image = PixelPipeline.newImage(2, 2, layout.hasAlpha());
    PixelPipeline.writeByteRows(image, 0, 2, buffer, 5, stride, layout, premultiplied);
    for (int i = 0; i < argb.length; i++) {
      int expected = layout.hasAlpha() ? argb[i] : 0xFF000000 | argb[i];
      if ((expected >>> 24) == 0) expected = layout.hasAlpha() ? 0 : expected;
      int actual = image.getRGB(i % 2, i / 2);
      int tolerance = premultiplied ? 2 : 0; // premultiplication rounds
      for (int shift = 0; shift < 32; shift += 8) {
        int e = (expected >>> shift) & 0xFF, x = (actual >>> shift) & 0xFF;
        assertTrue(Math.abs(e - x) <= tolerance, String.format("%s pixel %d: expected %08x, was %08x", layout, i, expected, actual));
      }
    }
    assertThrows(IllegalArgumentException.class,
                 () -> PixelPipeline.writeByteRows(image, 0, 1, buffer, 0, 2 * bpp - 1, layout, false), "stride too small");
  }

  /** The quadrant layout: TL red, TR green, BL blue, BR white, 3x2 pixels + a marker at (0,0). */
  private static BufferedImage quadrants(int type) {
    BufferedImage image = new BufferedImage(3, 2, type);
    int[] rgb = {0xFF000000, 0xFF00FF00, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF, 0xFFFFFFFF};
    image.setRGB(0, 0, 3, 2, rgb, 0, 3);
    return image;
  }

  /** Expected pixels (row-major) of the 3x2 test image after each EXIF orientation. */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "1 | 3x2 | 000000 00ff00 00ff00 0000ff ffffff ffffff",
      "2 | 3x2 | 00ff00 00ff00 000000 ffffff ffffff 0000ff",
      "3 | 3x2 | ffffff ffffff 0000ff 00ff00 00ff00 000000",
      "4 | 3x2 | 0000ff ffffff ffffff 000000 00ff00 00ff00",
      "5 | 2x3 | 000000 0000ff 00ff00 ffffff 00ff00 ffffff",
      "6 | 2x3 | 0000ff 000000 ffffff 00ff00 ffffff 00ff00",
      "7 | 2x3 | ffffff 00ff00 ffffff 00ff00 0000ff 000000",
      "8 | 2x3 | 00ff00 ffffff 00ff00 ffffff 000000 0000ff",
  })
  void orientation(int orientation, String size, String expected) {
    for (int type : new int[]{BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR}) {
      BufferedImage source = quadrants(type);
      BufferedImage result = PixelPipeline.applyOrientation(source, orientation);
      assertEquals(size, result.getWidth() + "x" + result.getHeight(), "type " + type);
      StringBuilder actual = new StringBuilder();
      for (int y = 0; y < result.getHeight(); y++) {
        for (int x = 0; x < result.getWidth(); x++) {
          if (actual.length() > 0) actual.append(' ');
          actual.append(String.format("%06x", result.getRGB(x, y) & 0xFFFFFF));
        }
      }
      assertEquals(expected, actual.toString(), "orientation " + orientation + ", type " + type);
      if (orientation == 1) assertSame(source, result);
      else assertEquals(type == BufferedImage.TYPE_INT_ARGB ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB, result.getType());
    }
  }

  @Test
  void invalidOrientationsAreIgnored() {
    BufferedImage source = quadrants(BufferedImage.TYPE_INT_RGB);
    assertSame(source, PixelPipeline.applyOrientation(source, 0));
    assertSame(source, PixelPipeline.applyOrientation(source, 9));
  }

  /** Orientation of an image larger than one strip matches a straightforward per-pixel rotation. */
  @Test
  void orientationOfALargeImage() {
    int w = 1500, h = 1000; // 1.5 MP: two strips
    BufferedImage source = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    int[] data = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
    for (int i = 0; i < data.length; i++) data[i] = (int) (i * 2654435761L);
    int[] expected = new int[w * h];
    for (int y = 0; y < w; y++) {
      for (int x = 0; x < h; x++) expected[y * h + x] = source.getRGB(y, h - 1 - x); // rotate 90 clockwise
    }
    BufferedImage rotated = PixelPipeline.applyOrientation(source, 6);
    assertArrayEquals(expected, rotated.getRGB(0, 0, h, w, null, 0, h));
  }

  @ParameterizedTest
  @CsvSource({"600, 400, 150, 150x100", "400, 600, 150, 100x150", "600, 400, 600, 600x400", "600, 400, 0, 600x400",
              "5000, 3, 100, 100x1", "600, 400, 599, 599x399"})
  void downscale(int width, int height, int max, String expected) {
    BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    BufferedImage result = PixelPipeline.downscale(source, max);
    assertEquals(expected, result.getWidth() + "x" + result.getHeight());
    if (max == 0 || Math.max(width, height) <= max) assertSame(source, result);
  }

  @Test
  void downscaleKeepsColorsAndAlpha() {
    BufferedImage source = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = source.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, 200, 200);
    g.setColor(new Color(0, 0, 255, 128));
    g.fillRect(200, 0, 200, 200);
    g.dispose();
    BufferedImage result = PixelPipeline.downscale(source, 40);
    assertEquals(BufferedImage.TYPE_INT_ARGB, result.getType());
    assertEquals(0xFFFF0000, result.getRGB(5, 10));
    int blue = result.getRGB(35, 10);
    assertTrue(Math.abs((blue >>> 24) - 128) <= 1 && (blue & 0xFF) >= 250, String.format("%08x", blue));
  }

  @Test
  void convertToSrgbFromSrgbIsIdentity() throws IOException {
    BufferedImage image = quadrants(BufferedImage.TYPE_INT_ARGB);
    int[] before = image.getRGB(0, 0, 3, 2, null, 0, 3);
    PixelPipeline.convertToSrgb(image, ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
    int[] after = image.getRGB(0, 0, 3, 2, null, 0, 3);
    for (int i = 0; i < before.length; i++) {
      for (int shift = 0; shift < 32; shift += 8) {
        assertTrue(Math.abs(((before[i] >>> shift) & 0xFF) - ((after[i] >>> shift) & 0xFF)) <= 1,
                   String.format("%08x -> %08x", before[i], after[i]));
      }
    }
  }

  /** Linear-light RGB values are darker when shown as sRGB: 50% linear grey is about 188 in sRGB. */
  @Test
  void convertToSrgbAppliesTheProfile() throws IOException {
    BufferedImage image = new BufferedImage(1500, 1000, BufferedImage.TYPE_INT_RGB); // two strips
    Graphics2D g = image.createGraphics();
    g.setColor(new Color(128, 128, 128));
    g.fillRect(0, 0, 1500, 1000);
    g.dispose();
    PixelPipeline.convertToSrgb(image, ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData());
    for (int y : new int[]{0, 999}) {
      int grey = image.getRGB(750, y) & 0xFF;
      assertTrue(grey > 175 && grey < 200, "linear 128 -> sRGB " + grey);
    }
    assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
  }

  @ParameterizedTest
  @ValueSource(strings = {"garbage", "gray"})
  void convertToSrgbRejectsUnusableProfiles(String kind) {
    byte[] profile = kind.equals("gray") ? ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData() : new byte[]{1, 2, 3, 4};
    BufferedImage image = quadrants(BufferedImage.TYPE_INT_RGB);
    assertThrows(IOException.class, () -> PixelPipeline.convertToSrgb(image, profile));
  }
}
