package cn.yooss.heic.linux;

import cn.yooss.heic.backend.PixelPipeline.ByteLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** libheif's interleaved plane to {@link BufferedImage}, with streaming downscaling (pure Java, runs on every OS). */
class PlaneConverterTest {
  /** A plane with padded rows; {@code pixel(x, y)} gives the RGBA bytes. */
  private static final class Plane implements PlaneConverter.Rows {
    final int width, height, stride;
    final ByteLayout layout;
    final byte[] bytes;
    final List<String> reads = new ArrayList<>();

    Plane(int width, int height, ByteLayout layout, PixelSource source) {
      this.width = width;
      this.height = height;
      this.layout = layout;
      this.stride = width * layout.bytesPerPixel() + 13; // padding, like libheif's aligned rows
      this.bytes = new byte[stride * height];
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          int[] rgba = source.pixel(x, y);
          int at = y * stride + x * layout.bytesPerPixel();
          for (int c = 0; c < layout.bytesPerPixel(); c++) bytes[at + c] = (byte) rgba[c];
        }
      }
    }

    @Override
    public void read(int y0, int rows, byte[] target) {
      reads.add(y0 + "+" + rows);
      System.arraycopy(bytes, y0 * stride, target, 0, rows * stride);
    }

    BufferedImage convert(boolean premultiplied, boolean alpha, int maxPixelSize) throws IOException {
      return PlaneConverter.convert(width, height, stride, layout, premultiplied, alpha, maxPixelSize, this);
    }
  }

  private interface PixelSource {
    int[] pixel(int x, int y);
  }

  @Test
  void fullSizeCopy() throws IOException {
    Plane rgb = new Plane(5, 3, ByteLayout.RGB, (x, y) -> new int[]{x * 50, y * 100, 7});
    BufferedImage image = rgb.convert(false, false, 0);
    assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
    assertEquals(0xFFC8C807, image.getRGB(4, 2));
    assertEquals(0xFF000007, image.getRGB(0, 0));

    Plane rgba = new Plane(4, 2, ByteLayout.RGBA, (x, y) -> new int[]{200, 100, 50, x * 80});
    BufferedImage straight = rgba.convert(false, true, 0);
    assertEquals(BufferedImage.TYPE_INT_ARGB, straight.getType());
    assertEquals(0xF0C86432, straight.getRGB(3, 1));
    assertEquals(0x00C86432, straight.getRGB(0, 0) | 0x00C86432); // transparent: color undefined
    // premultiplied 100/255 at alpha 128 is straight 199
    Plane pre = new Plane(1, 1, ByteLayout.RGBA, (x, y) -> new int[]{100, 50, 0, 128});
    assertEquals(0x80C76400, pre.convert(true, true, 0).getRGB(0, 0));
    // RGBA into an opaque image: alpha dropped; RGB into an image with alpha: opaque
    assertEquals(0xFFC86432, rgba.convert(false, false, 0).getRGB(3, 1));
    assertEquals(0xFFC8C807, rgb.convert(false, true, 0).getRGB(4, 2));
  }

  @ParameterizedTest
  @CsvSource({
      "600, 400, 150, 150, 100",
      "400, 600, 150, 100, 150",
      "600, 400, 599, 599, 399",
      "600, 400, 600, 600, 400",
      "2000, 1200, 7, 7, 4",
      "4032, 3024, 64, 64, 48",
      "3, 1000, 10, 1, 10",
      "1000, 1, 10, 10, 1",
  })
  void targetSizes(int width, int height, int maxPixelSize, int expectedW, int expectedH) throws IOException {
    Plane plane = new Plane(width, height, ByteLayout.RGB, (x, y) -> new int[]{10, 20, 30});
    BufferedImage image = plane.convert(false, false, maxPixelSize);
    assertEquals(expectedW + "x" + expectedH, image.getWidth() + "x" + image.getHeight());
    assertEquals(0xFF0A141E, image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
  }

  /** A 1-pixel checkerboard averages to gray (no aliasing), a two-color split keeps its colors. */
  @Test
  void boxFilterAverages() throws IOException {
    Plane checker = new Plane(640, 480, ByteLayout.RGB, (x, y) -> ((x + y) & 1) == 0 ? new int[]{255, 255, 255} : new int[]{0, 0, 0});
    BufferedImage gray = checker.convert(false, false, 64);
    assertEquals("64x48", gray.getWidth() + "x" + gray.getHeight());
    for (int y = 0; y < gray.getHeight(); y++) {
      for (int x = 0; x < gray.getWidth(); x++) {
        int value = gray.getRGB(x, y) & 0xFF;
        assertTrue(Math.abs(value - 128) <= 2, "gray at " + x + "," + y + ": " + value);
      }
    }
    Plane split = new Plane(1000, 500, ByteLayout.RGB, (x, y) -> x < 500 ? new int[]{255, 0, 0} : new int[]{0, 0, 255});
    BufferedImage small = split.convert(false, false, 100);
    assertEquals(0xFFFF0000, small.getRGB(10, 25));
    assertEquals(0xFF0000FF, small.getRGB(90, 25));
  }

  /** Transparent pixels (whatever their color) do not bleed into their opaque neighbors. */
  @Test
  void alphaWeighting() throws IOException {
    Plane plane = new Plane(400, 400, ByteLayout.RGBA, (x, y) -> (x & 1) == 0 ? new int[]{0, 0, 255, 255} : new int[]{255, 0, 0, 0});
    BufferedImage image = plane.convert(false, true, 40);
    int p = image.getRGB(20, 20);
    assertTrue(Math.abs((p >>> 24) - 128) <= 2, Integer.toHexString(p));
    assertEquals(0x0000FF, p & 0xFFFFFF, "straight blue, no red: " + Integer.toHexString(p));
    // the same plane, premultiplied (the transparent pixels are 0,0,0,0 then)
    Plane pre = new Plane(400, 400, ByteLayout.RGBA, (x, y) -> (x & 1) == 0 ? new int[]{0, 0, 255, 255} : new int[]{0, 0, 0, 0});
    int q = pre.convert(true, true, 40).getRGB(20, 20);
    assertEquals(p, q);
    // fully transparent blocks
    Plane clear = new Plane(100, 100, ByteLayout.RGBA, (x, y) -> new int[]{255, 255, 255, 0});
    assertEquals(0, clear.convert(false, true, 10).getRGB(5, 5) >>> 24);
  }

  /** Rows are streamed in strips of at most STRIP_BYTES, never the whole plane at once. */
  @Test
  void readsInStrips() throws IOException {
    int width = 1024;
    int height = 2500;
    Plane plane = new Plane(width, height, ByteLayout.RGBA, (x, y) -> new int[]{y % 256, x % 256, 0, 255});
    BufferedImage image = plane.convert(false, false, 0);
    assertTrue(plane.reads.size() >= 3, plane.reads.toString());
    int rowsPerStrip = PlaneConverter.STRIP_BYTES / plane.stride;
    assertEquals("0+" + rowsPerStrip, plane.reads.get(0));
    assertEquals(0xFF000000 | (2499 % 256) << 16 | (1000 % 256) << 8, image.getRGB(1000, 2499));
    plane.reads.clear();
    BufferedImage small = plane.convert(false, false, 100);
    assertFalse(plane.reads.isEmpty());
    for (String read : plane.reads) assertTrue(Integer.parseInt(read.substring(read.indexOf('+') + 1)) <= rowsPerStrip, read);
    assertEquals("41x100", small.getWidth() + "x" + small.getHeight());
  }

  @Test
  void invalidPlanes() {
    PlaneConverter.Rows none = (y0, rows, target) -> { };
    assertThrows(IOException.class, () -> PlaneConverter.convert(0, 10, 40, ByteLayout.RGB, false, false, 0, none));
    assertThrows(IOException.class, () -> PlaneConverter.convert(10, 10, 29, ByteLayout.RGB, false, false, 0, none));
    assertThrows(IOException.class, () -> PlaneConverter.convert(10, 10, 39, ByteLayout.RGBA, false, false, 0, none));
  }
}
