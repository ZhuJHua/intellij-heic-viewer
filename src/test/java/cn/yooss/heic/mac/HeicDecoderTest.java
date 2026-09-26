package cn.yooss.heic.mac;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifImageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The macOS decoder (ImageIO.framework through the IDE's JNA) itself; see HeifBackendContractTest for the backend API. */
@EnabledOnOs(OS.MAC)
class HeicDecoderTest {
  @Test
  void nativeBridgeIsJna() throws IOException {
    String bridge = HeicDecoder.nativeBridge();
    assertTrue(bridge.startsWith("JNA 5."), bridge);
    String arch = System.getProperty("os.arch");
    assertTrue(bridge.endsWith(arch.equals("aarch64") ? "(arm64 HFA)" : "(x86_64 stack)"), bridge);
  }

  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,     600, 400, 1, 600, 400, false, 8",
      "rgb_libheif.heic,  600, 400, 1, 600, 400, false, 8",
      "exif3_apple.heic,  600, 400, 3, 600, 400, false, 8",
      "exif5_apple.heic,  600, 400, 5, 400, 600, false, 8",
      "exif6_apple.heic,  600, 400, 6, 400, 600, false, 8",
      "rot90_irot.heic,   600, 400, 6, 400, 600, false, 8",
      "fliph_imir.heic,   600, 400, 2, 600, 400, false, 8",
      "alpha_sips.heic,   400, 300, 1, 400, 300, true,  8",
      "alpha_libheif.heic,400, 300, 1, 400, 300, true,  8",
      "rgb16_sips.heic,   512, 256, 1, 512, 256, false, 10",
      "ten_bit.heic,      512, 256, 1, 512, 256, false, 10",
      "grid_libheif.heic, 600, 400, 1, 600, 400, false, 8",
      "multi.heic,        600, 400, 1, 600, 400, false, 8",
      "seq.heics,         600, 400, 1, 600, 400, false, 8",
  })
  void readInfo(String name, int rawWidth, int rawHeight, int orientation, int width, int height, boolean alpha, int depth)
      throws IOException {
    HeifImageInfo info = HeicDecoder.readInfo(Fixtures.bytes(name));
    assertEquals(rawWidth, info.rawWidth(), "rawWidth");
    assertEquals(rawHeight, info.rawHeight(), "rawHeight");
    assertEquals(orientation, info.orientation(), "orientation");
    assertEquals(width, info.width(), "display width");
    assertEquals(height, info.height(), "display height");
    assertEquals(alpha, info.hasAlpha(), "hasAlpha");
    assertEquals(depth, info.bitDepth(), "bitDepth");
    assertEquals(0, info.primaryIndex());
  }

  @Test
  void typeIdentifierAndImageCount() throws IOException {
    assertEquals("public.heic", HeicDecoder.readInfo(Fixtures.bytes("rgb_sips.heic")).typeIdentifier());
    assertEquals("public.heics", HeicDecoder.readInfo(Fixtures.bytes("seq.heics")).typeIdentifier());
    assertEquals(2, HeicDecoder.readInfo(Fixtures.bytes("multi.heic")).imageCount());
  }

  /** Expected layouts of the quadrant fixture after orientation, verified against sips/Preview. */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "rgb_sips.heic     | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
      "rgb_libheif.heic  | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
      "exif3_apple.heic  | 600x400 TL=white TR=blue BL=green BR=red marker=BR",
      "exif5_apple.heic  | 400x600 TL=red TR=blue BL=green BR=white marker=TL",
      "exif6_apple.heic  | 400x600 TL=blue TR=red BL=white BR=green marker=TR",
      "rot90_irot.heic   | 400x600 TL=blue TR=red BL=white BR=green marker=TR",
      "fliph_imir.heic   | 600x400 TL=green TR=red BL=white BR=blue marker=TR",
      "grid_libheif.heic | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
      "multi.heic        | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
      "seq.heics         | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
  })
  void orientationIsApplied(String name, String expectedLayout) throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes(name), 0);
    assertEquals(expectedLayout, Fixtures.layout(image));
    assertEquals(BufferedImage.TYPE_INT_RGB, image.getType(), "opaque images are TYPE_INT_RGB");
    assertEquals(24, image.getColorModel().getPixelSize());
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb_sips.heic", "rgb_libheif.heic", "grid_libheif.heic", "multi.heic", "seq.heics"})
  void pixelsMatchTheSourcePng(String name) throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes(name), 0);
    double mean = Fixtures.meanDifference(image, Fixtures.png("rgb.png"));
    // Lossy HEVC with 4:2:0 chroma only blurs the sharp quadrant edges.
    assertTrue(mean < 2.0, "mean difference " + mean);
    assertPixel(0xFF804020, image.getRGB(300, 200), 0, 6); // brown centre patch
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb16_sips.heic", "ten_bit.heic"})
  void tenBitImagesDecodeToEightBitSrgb(String name) throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes(name), 0);
    assertEquals(512, image.getWidth());
    assertEquals(256, image.getHeight());
    assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
    double mean = Fixtures.meanDifference(image, Fixtures.png("rgb16.png"));
    assertTrue(mean < 1.0, "mean difference to the 16-bit PNG source " + mean);
  }

  @ParameterizedTest
  @ValueSource(strings = {"alpha_sips.heic", "alpha_libheif.heic"})
  void alphaIsStraightNotPremultiplied(String name) throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes(name), 0);
    assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
    assertFalse(image.isAlphaPremultiplied());

    // Raw raster values (not getRGB) must already be un-premultiplied.
    int[] raw = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    int w = image.getWidth();
    assertPixel(0x00000000, raw[150 * w + 50], 0, 0);   // transparent
    assertPixel(0x80FF0000, raw[150 * w + 150], 3, 8);  // red at alpha 128
    assertPixel(0xFF0000FF, raw[150 * w + 250], 3, 8);  // opaque blue
    assertPixel(0x4000FF00, raw[150 * w + 350], 3, 12); // green at alpha 64

    double mean = Fixtures.meanDifference(image, Fixtures.png("alpha.png"));
    assertTrue(mean < 3.0, "mean difference " + mean);
  }

  private static void assertPixel(int expected, int actual, int alphaTolerance, int colorTolerance) {
    String message = String.format("expected %08x but was %08x", expected, actual);
    assertTrue(Math.abs((expected >>> 24) - (actual >>> 24)) <= alphaTolerance, message);
    for (int shift = 0; shift < 24; shift += 8) {
      assertTrue(Math.abs(((expected >> shift) & 255) - ((actual >> shift) & 255)) <= colorTolerance, message);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,    150, 150, 100",
      "exif6_apple.heic, 150, 100, 150",
      "grid_libheif.heic,300, 300, 200",
      "rgb_sips.heic,    600, 600, 400",
      "rgb_sips.heic,   5000, 600, 400", // never upscaled
      "rgb_sips.heic,      1,   3,   2", // clamped: ImageIO.framework needs >= 2 px on the shorter side
  })
  void maxPixelSizeDownscales(String name, int maxPixelSize, int width, int height) throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes(name), maxPixelSize);
    assertEquals(width + "x" + height, image.getWidth() + "x" + image.getHeight());
  }

  @Test
  void downscaledImageKeepsLayout() throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes("exif6_apple.heic"), 300);
    assertEquals("200x300 TL=blue TR=red BL=white BR=green marker=TR", Fixtures.layout(image));
  }

  @Test
  void thumbnail() throws IOException {
    BufferedImage portrait = HeicDecoder.decodeThumbnail(Fixtures.bytes("exif6_apple.heic"), 64);
    assertTrue(portrait.getHeight() <= 64 && portrait.getHeight() > portrait.getWidth(),
               "oriented portrait thumbnail, was " + portrait.getWidth() + "x" + portrait.getHeight());
    BufferedImage alpha = HeicDecoder.decodeThumbnail(Fixtures.bytes("alpha_sips.heic"), 32);
    assertEquals(BufferedImage.TYPE_INT_ARGB, alpha.getType());
    assertTrue(Math.max(alpha.getWidth(), alpha.getHeight()) <= 32);
    assertThrows(IllegalArgumentException.class, () -> HeicDecoder.decodeThumbnail(Fixtures.bytes("rgb_sips.heic"), 0));
  }

  private static final int[] BAND_COLORS = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF};

  /** 2000x1200 = 2.4 MP: drawn once, copied into the Java image in 3 chunks of 524 rows (2^20 pixels each). */
  @Test
  void multiStripRendering() throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes("bands_2000x1200.heic"), 0);
    assertEquals(2000, image.getWidth());
    assertEquals(1200, image.getHeight());
    for (int band = 0; band < 12; band++) {
      for (int x : new int[]{100, 1000, 1990}) {
        assertPixel(0xFF000000 | BAND_COLORS[band % 6], image.getRGB(x, band * 100 + 50), 0, 6);
      }
    }
    // rows right at the chunk boundaries (523/524 and 1047/1048)
    assertPixel(0xFF000000 | BAND_COLORS[5], image.getRGB(1000, 523), 0, 6);
    assertPixel(0xFF000000 | BAND_COLORS[5], image.getRGB(1000, 524), 0, 6);
    assertPixel(0xFF000000 | BAND_COLORS[4], image.getRGB(1000, 1047), 0, 6);
    assertPixel(0xFF000000 | BAND_COLORS[4], image.getRGB(1000, 1048), 0, 6);
    assertPixel(0xFF000000, image.getRGB(20, 20), 0, 16); // marker
    double mean = Fixtures.meanDifference(image, Fixtures.png("bands.png"));
    assertTrue(mean < 3.0, "mean difference " + mean);
  }

  /** The chunks the pixels are copied in do not change them, whatever their height. */
  @ParameterizedTest
  @ValueSource(strings = {"bands_2000x1200.heic", "bands_exif6.heic", "alpha_sips.heic", "exif5_apple.heic"})
  void stripHeightDoesNotChangePixels(String name) throws IOException {
    byte[] data = Fixtures.bytes(name);
    int[] single = pixels(HeicDecoder.decode(data, 0, false, Integer.MAX_VALUE));
    for (int stripPixels : new int[]{1, 7 * 2000, 100_000, 1 << 20}) {
      assertArrayEquals(single, pixels(HeicDecoder.decode(data, 0, false, stripPixels)), "strip pixels " + stripPixels);
    }
  }

  /**
   * A full-size (or ImageIO-scaled) decode draws the image once, into a bitmap of its size: a draw of an image that
   * ImageIO.framework could not cache (a malformed file whose {@code ispe} does not match the coded image) decodes the
   * whole image again, so eight strips took eight times as long. The Java side still copies small chunks.
   */
  @Test
  void aDecodeDrawsOnce() throws Throwable {
    byte[] data = Fixtures.bytes("bands_2000x1200.heic");
    int[] expected = pixels(HeicDecoder.decode(data, 0, false, Integer.MAX_VALUE));
    for (int stripPixels : new int[]{1, 1000, 1 << 20}) {
      Counting counting = new Counting(0);
      BufferedImage image = HeicDecoder.decode(new HeicDecoder.Bound(counting.api), data, 0, false, stripPixels);
      assertEquals(1, counting.draws, "strip pixels " + stripPixels);
      assertArrayEquals(expected, pixels(image));
    }
    Counting scaled = new Counting(0);
    assertEquals(500, HeicDecoder.decode(new HeicDecoder.Bound(scaled.api), data, 500, false, 1).getWidth());
    assertEquals(1, scaled.draws);
  }

  /**
   * If the full-size bitmap cannot be allocated, the image is drawn in strips, at most {@link HeicDecoder#MAX_DRAWS} of
   * them however small the strip budget, with the same pixels.
   */
  @Test
  void stripsWhenTheFullSizeBitmapCannotBeAllocated() throws Throwable {
    // The strip plan: at most MAX_DRAWS strips, e.g. for the crafted file below at the size the old 64 MP budget gave
    // it (3952x16187, which used to be 62 strips: about a minute when every draw decodes again).
    int[] plan = HeicDecoder.stripPlan(3952, 16187, cn.yooss.heic.backend.PixelPipeline.STRIP_PIXELS);
    assertTrue((16187 + plan[0] - 1) / plan[0] <= HeicDecoder.MAX_DRAWS, "rows per strip " + plan[0]);
    assertEquals(cn.yooss.heic.backend.PixelPipeline.stripRows(3952, 16187, cn.yooss.heic.backend.PixelPipeline.STRIP_PIXELS),
                 plan[1], "copied in ~1M-pixel chunks");
    for (int[] size : new int[][]{{1, 1}, {7, 3}, {2000, 1200}, {16384, 16384}, {8000, 8000}, {100_000, 2}, {3, 100_000}}) {
      for (int stripPixels : new int[]{1, 1000, 1 << 20, Integer.MAX_VALUE}) {
        int[] p = HeicDecoder.stripPlan(size[0], size[1], stripPixels);
        String what = size[0] + "x" + size[1] + " at " + stripPixels + ": " + Arrays.toString(p);
        assertTrue(p[0] >= 1 && p[1] >= 1 && p[1] <= p[0] && p[0] <= size[1], what);
        assertTrue((size[1] + p[0] - 1) / p[0] <= HeicDecoder.MAX_DRAWS, what);
      }
    }

    // A real decode whose full-size bitmap (2000 x 1200 x 4 bytes) cannot be allocated, with a strip budget of one
    // pixel: 8 draws of 150 rows, copied row by row, pixels unchanged.
    byte[] data = Fixtures.bytes("bands_2000x1200.heic");
    Counting counting = new Counting(4L * 2000 * 1200);
    BufferedImage image = HeicDecoder.decode(new HeicDecoder.Bound(counting.api), data, 0, false, 1);
    assertEquals(HeicDecoder.MAX_DRAWS, counting.draws);
    assertArrayEquals(pixels(HeicDecoder.decode(data, 0, false, Integer.MAX_VALUE)), pixels(image));
  }

  /**
   * Regression test of a crafted file (review finding A7): exif5_apple.heic, 1 kB, with an {@code ispe} of 50362 x 12301.
   * ImageIO.framework decodes such an image again on every draw (about a second each on an M4 Pro), so it took about
   * 8 s in eight strips (a minute when the IDE was busy). Drawn once, it takes about a second at any size.
   */
  @Test
  void malformedFileIsDrawnOnce() throws Throwable {
    byte[] crafted = Fixtures.withIspe(Fixtures.bytes("exif5_apple.heic"), 600, 400, 50362, 12301);
    HeifImageInfo info = HeicDecoder.readInfo(crafted);
    assertEquals("12301x50362", info.width() + "x" + info.height(), "orientation 5 swaps the declared size");
    Counting counting = new Counting(0);
    long start = System.nanoTime();
    BufferedImage image = HeicDecoder.decode(new HeicDecoder.Bound(counting.api), crafted, 4096, false,
                                             cn.yooss.heic.backend.PixelPipeline.STRIP_PIXELS);
    long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    assertEquals(4096, image.getHeight());
    assertTrue(Math.abs(image.getWidth() - 1000) <= 1, image.getWidth() + "x" + image.getHeight());
    assertEquals(1, counting.draws, "one draw, one decode");
    assertTrue(seconds < 60, seconds + " s");
  }

  /**
   * ImageIO leaves parts of a malformed image undrawn; those parts must come out black, never as whatever the native
   * buffer held before (pixels of an image decoded earlier). The buffers are filled with a marker before the draw.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void undrawnPixelsAreNeverStaleMemory(int stripPixels) throws Throwable {
    byte[] crafted = Fixtures.withIspe(Fixtures.bytes("exif5_apple.heic"), 600, 400, 50362, 12301);
    Counting counting = new Counting(0, (byte) 0x5A);
    BufferedImage image = HeicDecoder.decode(new HeicDecoder.Bound(counting.api), crafted, 4096, false,
                                             stripPixels == 0 ? cn.yooss.heic.backend.PixelPipeline.STRIP_PIXELS : 1 << 16);
    int stale = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) & 0xFFFFFF) == 0x5A5A5A) stale++;
      }
    }
    assertEquals(0, stale, "pixels showing the marker left in the native buffer");
  }

  /** The real binding, counting the draws; {@code malloc} of {@code failingSize} bytes fails (0: none fails). */
  private static final class Counting {
    final MacApi api;
    int draws;

    Counting(long failingSize) {
      this(failingSize, (byte) 0);
    }

    /** {@code poison != 0}: every buffer from {@code malloc} is filled with it, like reused memory would be. */
    Counting(long failingSize, byte poison) {
      MacApi real = new cn.yooss.heic.mac.jna.JnaMacApi();
      api = (MacApi) java.lang.reflect.Proxy.newProxyInstance(
        MacApi.class.getClassLoader(), new Class<?>[]{MacApi.class}, (proxy, method, args) -> {
          if (method.getName().equals("cgContextDrawImage")) draws++;
          if (method.getName().equals("malloc") && failingSize != 0 && (Long) args[0] == failingSize) return 0L;
          if (method.getName().equals("malloc") && poison != 0) {
            long address = real.malloc((Long) args[0]);
            if (address != 0) new com.sun.jna.Pointer(address).setMemory(0, (Long) args[0], poison);
            return address;
          }
          try {
            return method.invoke(real, args);
          }
          catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
        });
    }
  }

  /**
   * An image with alpha that is requested smaller is downscaled alpha-weighted, by the plugin rather than ImageIO's
   * thumbnail scaler, which darkens the edges on some Macs (red at a quarter alpha: 128 on the GitHub runners). Also
   * for thumbnails; the strip layout (at most MAX_DRAWS draws) does not change the pixels.
   */
  @ParameterizedTest
  @ValueSource(strings = {"alpha_libheif.heic", "alpha_sips.heic"})
  void downscaledAlphaIsAlphaWeighted(String name) throws Throwable {
    byte[] data = Fixtures.bytes(name);
    for (BufferedImage image : new BufferedImage[]{HeicDecoder.decode(data, 90), HeicDecoder.decodeThumbnail(data, 90)}) {
      assertEquals("90x68", image.getWidth() + "x" + image.getHeight());
      assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
      int edge = image.getRGB(22, 34); // source columns 97.8 to 102.2: half transparent, half red at alpha 128
      String message = name + ": " + Integer.toHexString(edge);
      assertTrue(Math.abs((edge >>> 24) - 64) <= 16, message);
      // about 255 (0x40ef0404 on the GitHub runners, whose decoder gives the red a little green and blue); the old path
      // gave 0x40800404 there and 0x44bf0000 on an M-series Mac
      assertTrue(((edge >> 16) & 0xFF) >= 220 && ((edge >> 8) & 0xFF) <= 40 && (edge & 0xFF) <= 40, message);
      assertEquals(0, image.getRGB(0, 0) >>> 24, "transparent corner");
    }

    Counting counting = new Counting(0);
    BufferedImage stripped = HeicDecoder.decode(new HeicDecoder.Bound(counting.api), data, 90, false, 1);
    assertEquals(HeicDecoder.MAX_DRAWS, counting.draws);
    assertArrayEquals(pixels(HeicDecoder.decode(data, 90)), pixels(stripped));
  }

  /** Which decodes are downscaled alpha-weighted: with alpha, smaller than the image, at most 64 megapixels. */
  @Test
  void alphaWeightedOnlyForSmallerImagesWithAlphaOfAtMost64Megapixels() {
    HeifImageInfo alpha = new HeifImageInfo("public.heic", 1, 0, 8000, 6000, 1, 8, true);
    assertTrue(HeicDecoder.isAlphaWeighted(alpha, 256));
    assertFalse(HeicDecoder.isAlphaWeighted(alpha, 0), "full size");
    assertFalse(HeicDecoder.isAlphaWeighted(alpha, 8000), "not smaller");
    assertFalse(HeicDecoder.isAlphaWeighted(new HeifImageInfo("public.heic", 1, 0, 8000, 6000, 1, 8, false), 256));
    assertTrue(HeicDecoder.isAlphaWeighted(new HeifImageInfo("public.heic", 1, 0, 8000, 8000, 1, 8, true), 256));
    assertFalse(HeicDecoder.isAlphaWeighted(new HeifImageInfo("public.heic", 1, 0, 8001, 8000, 1, 8, true), 256),
                "above 64 MP: ImageIO's scaler, whose decode needs no second full-size copy");
  }

  /** Orientation 6 (rotate 90 degrees clockwise) on an image that is copied in several chunks. */
  @Test
  void multiStripRenderingWithOrientation() throws IOException {
    BufferedImage image = HeicDecoder.decode(Fixtures.bytes("bands_exif6.heic"), 0);
    assertEquals(1200, image.getWidth());
    assertEquals(2000, image.getHeight());
    for (int column = 0; column < 12; column++) {
      int band = 11 - column; // the bottom band of the stored image becomes the left column
      for (int y : new int[]{100, 900, 1990}) {
        assertPixel(0xFF000000 | BAND_COLORS[band % 6], image.getRGB(column * 100 + 50, y), 0, 6);
      }
    }
    assertPixel(0xFF000000, image.getRGB(1180, 20), 0, 16); // the top-left marker is now top-right
    double mean = Fixtures.meanDifference(image, rotateClockwise(Fixtures.png("bands.png")));
    assertTrue(mean < 3.0, "mean difference " + mean);
  }

  private static BufferedImage rotateClockwise(BufferedImage source) {
    int w = source.getWidth(), h = source.getHeight();
    BufferedImage rotated = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < w; y++) {
      for (int x = 0; x < h; x++) {
        rotated.setRGB(x, y, source.getRGB(y, h - 1 - x));
      }
    }
    return rotated;
  }

  @ParameterizedTest
  @ValueSource(strings = {"garbage.heic", "header_only.heic"})
  void invalidFilesFailWithIOException(String name) {
    byte[] data = Fixtures.bytes(name);
    assertThrows(IOException.class, () -> HeicDecoder.readInfo(data));
    assertThrows(IOException.class, () -> HeicDecoder.decode(data, 0));
    assertThrows(IOException.class, () -> HeicDecoder.decodeThumbnail(data, 64));
  }

  /**
   * ImageIO.framework picks the codec by content: data that is not HEIF (whatever the file is called) must be refused
   * before it reaches another native codec.
   */
  @ParameterizedTest
  @ValueSource(strings = {"png", "tiff", "bmp", "gif", "jpeg"})
  void otherImageFormatsAreRejectedBeforeAnyNativeCall(String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(Fixtures.png("rgb.png"), format, out), format);
    byte[] data = out.toByteArray();
    for (Executable decode : List.<Executable>of(() -> HeicDecoder.readInfo(data), () -> HeicDecoder.decode(data, 0),
                                                 () -> HeicDecoder.decodeThumbnail(data, 64))) {
      IOException e = assertThrows(IOException.class, decode, format);
      assertEquals("Not a HEIC/HEIF file", e.getMessage(), format);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb.avif", "alpha.avif", "rgb.png"})
  void avifAndPngFixturesAreRejected(String name) {
    IOException e = assertThrows(IOException.class, () -> HeicDecoder.decode(Fixtures.bytes(name), 0));
    assertEquals("Not a HEIC/HEIF file", e.getMessage(), name);
  }

  @Test
  void onlyHeifFamilyTypesAreAccepted() {
    for (String type : new String[]{"public.heic", "public.heics", "public.heif", "public.avci"}) {
      assertTrue(HeicDecoder.isHeifType(type), type);
    }
    for (String type : new String[]{"com.truevision.tga-image", "com.adobe.photoshop-image", "public.tiff", "public.png",
                                    "public.jpeg", "", null}) {
      assertFalse(HeicDecoder.isHeifType(type), type);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 8, 16, 100, 300, 450, 600, 890, 900})
  void truncatedFilesFailWithIOException(int length) {
    byte[] data = Arrays.copyOf(Fixtures.bytes("rgb_sips.heic"), length); // 901 bytes in full
    assertThrows(IOException.class, () -> HeicDecoder.decode(data, 0));
  }

  @Test
  void truncatedGridAndMultiImageFiles() {
    for (String name : new String[]{"grid_libheif.heic", "multi.heic", "alpha_libheif.heic", "seq.heics"}) {
      byte[] full = Fixtures.bytes(name);
      for (int length : new int[]{full.length / 3, full.length / 2, full.length - 1}) {
        byte[] data = Arrays.copyOf(full, length);
        assertThrows(IOException.class, () -> HeicDecoder.decode(data, 0), name + " cut at " + length);
      }
    }
  }

  @Test
  void emptyInput() {
    assertThrows(IOException.class, () -> HeicDecoder.decode(new byte[0], 0));
    assertThrows(IOException.class, () -> HeicDecoder.readInfo(new byte[0]));
    assertThrows(IOException.class, () -> HeicDecoder.decode(null, 0));
  }

  @Test
  void randomBytesAfterAValidHeader() {
    byte[] data = Arrays.copyOf(Fixtures.bytes("rgb_sips.heic"), 901);
    for (int i = 40; i < data.length; i++) data[i] = (byte) (i * 31 + 7); // keep ftyp, destroy the rest
    assertThrows(IOException.class, () -> HeicDecoder.decode(data, 0));
  }

  /**
   * Decodes on 16 threads give the pixels of a decode on one thread, within 2 levels per channel: on the macOS 15 Intel
   * runner, ImageIO's GPU conversion sometimes takes another path while other processes (the other test JVMs) decode,
   * e.g. 0xFF0000FF instead of 0xFF0000FE, even with the decodes serialized. (Unserialized, a concurrent decode of
   * alpha_sips.heic there came out as 0x80FB0000 instead of 0x80F90202, and the JVM crashed in VideoToolbox moments
   * later; see HeicDecoder.) All decodes have finished before the results are checked, so none runs into the next test.
   */
  @Test
  void concurrentDecodesAreIdentical() throws Exception {
    String[] names = {"rgb_sips.heic", "grid_libheif.heic", "alpha_sips.heic", "exif6_apple.heic"};
    List<int[]> expected = new ArrayList<>();
    for (String name : names) expected.add(pixels(HeicDecoder.decode(Fixtures.bytes(name), 0)));

    ExecutorService pool = Executors.newFixedThreadPool(16);
    List<int[]> results = new ArrayList<>();
    try {
      List<Future<int[]>> futures = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        String name = names[i % names.length];
        futures.add(pool.submit(() -> pixels(HeicDecoder.decode(Fixtures.bytes(name), 0))));
      }
      for (Future<int[]> future : futures) results.add(future.get(120, TimeUnit.SECONDS));
    }
    finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "decodes still running");
    }
    for (int i = 0; i < results.size(); i++) {
      int[] want = expected.get(i % names.length), got = results.get(i);
      assertEquals(want.length, got.length, names[i % names.length]);
      for (int p = 0; p < want.length; p++) {
        if (maxChannelDifference(want[p], got[p]) > 2) {
          assertEquals(String.format("%08X", want[p]), String.format("%08X", got[p]), names[i % names.length] + " pixel " + p);
        }
      }
    }
  }

  private static int maxChannelDifference(int a, int b) {
    int max = 0;
    for (int shift = 0; shift < 32; shift += 8) max = Math.max(max, Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255)));
    return max;
  }

  /** The ImageIO/CoreGraphics calls of the native part of a decode, which the gate keeps to one thread at a time. */
  private static final Set<String> NATIVE_DECODE_CALLS = Set.of(
    "cgImageSourceCreateWithData", "cgImageSourceCopyPropertiesAtIndex", "cgImageSourceCreateThumbnailAtIndex",
    "cgImageCreateWithImageInRect", "cgContextDrawImage");

  /** The real binding with {@code hook} called before every call (method name, arguments). */
  private interface Hook {
    Object before(String method, Object[] args) throws Throwable;
  }

  private static final Object PROCEED = new Object();

  private static MacApi hooked(Hook hook) {
    MacApi real = new cn.yooss.heic.mac.jna.JnaMacApi();
    return (MacApi) java.lang.reflect.Proxy.newProxyInstance(
      MacApi.class.getClassLoader(), new Class<?>[]{MacApi.class}, (proxy, method, args) -> {
        Object result = hook.before(method.getName(), args);
        if (result != PROCEED) return result;
        try {
          return method.invoke(real, args);
        }
        catch (java.lang.reflect.InvocationTargetException e) {
          throw e.getCause();
        }
      });
  }

  /**
   * Serialized, one decode at a time is in its native part, for every kind of decode: full size, scaled by ImageIO,
   * alpha-weighted (drawn in strips) and embedded thumbnails; only the Java side (copying the pixels) runs in parallel.
   * Not serialized, the same decodes do overlap (so the test can tell).
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void serializedNativeDecodesNeverOverlap(boolean serialized) throws Exception {
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger maxInside = new AtomicInteger();
    AtomicInteger copies = new AtomicInteger();
    AtomicInteger copiesInsideTheGate = new AtomicInteger();
    Set<String> seen = ConcurrentHashMap.newKeySet();
    MacApi real = new cn.yooss.heic.mac.jna.JnaMacApi();
    MacApi api = (MacApi) java.lang.reflect.Proxy.newProxyInstance(
      MacApi.class.getClassLoader(), new Class<?>[]{MacApi.class}, (proxy, method, args) -> {
        boolean decodeCall = NATIVE_DECODE_CALLS.contains(method.getName());
        if (decodeCall) {
          seen.add(method.getName());
          maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
          Thread.sleep(1); // widens the window in which ungated decodes would overlap
        }
        if (method.getName().equals("readInts")) {
          copies.incrementAndGet();
          if (HeicDecoder.isInNativeDecode()) copiesInsideTheGate.incrementAndGet();
        }
        try {
          return method.invoke(real, args);
        }
        catch (java.lang.reflect.InvocationTargetException e) {
          throw e.getCause();
        }
        finally {
          if (decodeCall) inside.decrementAndGet();
        }
      });
    HeicDecoder.Bound bound = new HeicDecoder.Bound(api, serialized);
    byte[] bands = Fixtures.bytes("bands_2000x1200.heic");
    byte[] alpha = Fixtures.bytes("alpha_sips.heic");
    byte[] thumb = Fixtures.bytes("thumb_irot.heic");
    int strip = cn.yooss.heic.backend.PixelPipeline.STRIP_PIXELS;
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<BufferedImage>> futures = new ArrayList<>();
      for (int i = 0; i < 48; i++) {
        int kind = i % 4;
        futures.add(pool.submit(() -> kind == 0 ? HeicDecoder.decode(bound, bands, 0, false, strip)
                                    : kind == 1 ? HeicDecoder.decode(bound, bands, 500, false, strip)
                                    : kind == 2 ? HeicDecoder.decode(bound, alpha, 90, true, 1) // strips: cropped draws
                                    : HeicDecoder.decode(bound, thumb, 64, true, strip)));
      }
      for (Future<BufferedImage> future : futures) future.get(120, TimeUnit.SECONDS);
    }
    finally {
      pool.shutdownNow();
    }
    assertEquals(NATIVE_DECODE_CALLS, seen, "every native decode call was made");
    if (serialized) {
      assertEquals(1, maxInside.get(), "native calls of different decodes overlapped");
      // Full-size and ImageIO-scaled decodes copy outside the gate, the alpha-weighted strips inside it.
      assertTrue(copiesInsideTheGate.get() > 0 && copiesInsideTheGate.get() < copies.get(),
                 copiesInsideTheGate + " of " + copies + " copies inside the gate");
    }
    else {
      assertTrue(maxInside.get() > 1, "unserialized decodes overlap: " + maxInside);
      assertEquals(0, copiesInsideTheGate.get());
    }
    assertFalse(HeicDecoder.isInNativeDecode());
  }

  /**
   * Decodes are serialized on Intel Macs, where concurrent ImageIO decodes crashed the JVM (see HeicDecoder), and not on
   * Apple silicon, where they did not; the system property heic.mac.serializeDecodes overrides it.
   */
  @Test
  void serializedOnIntelMacs() {
    assertTrue(HeicDecoder.serializeByDefault("x86_64", null));
    assertTrue(HeicDecoder.serializeByDefault("amd64", " "));
    assertFalse(HeicDecoder.serializeByDefault("aarch64", null));
    assertTrue(HeicDecoder.serializeByDefault("aarch64", "true"));
    assertFalse(HeicDecoder.serializeByDefault("x86_64", "false"));
    boolean intel = System.getProperty("os.arch").equals("x86_64") || System.getProperty("os.arch").equals("amd64");
    if (System.getProperty("heic.mac.serializeDecodes") == null) {
      assertEquals(intel, new HeicDecoder.Bound(new cn.yooss.heic.mac.jna.JnaMacApi()).serialized);
    }
  }

  /**
   * A decode waiting for the gate can be interrupted (the thumbnail executor is shut down when the plugin is unloaded):
   * it fails with an InterruptedIOException and its thread stays interrupted; the running decode is not disturbed. A
   * decode that fails inside the gate leaves it.
   */
  @Test
  void waitingForTheGateIsInterruptible() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    MacApi blocking = hooked((method, args) -> {
      if (method.equals("cgImageSourceCreateThumbnailAtIndex")) {
        entered.countDown();
        assertTrue(proceed.await(60, TimeUnit.SECONDS));
      }
      return PROCEED;
    });
    byte[] data = Fixtures.bytes("rgb_sips.heic");
    int strip = cn.yooss.heic.backend.PixelPipeline.STRIP_PIXELS;
    HeicDecoder.Bound serialized = new HeicDecoder.Bound(new cn.yooss.heic.mac.jna.JnaMacApi(), true);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<BufferedImage> first = pool.submit(() -> HeicDecoder.decode(new HeicDecoder.Bound(blocking, true), data, 0, false, strip));
      assertTrue(entered.await(60, TimeUnit.SECONDS), "the first decode reached ImageIO");

      AtomicReference<Thread> waiter = new AtomicReference<>();
      Future<Boolean> second = pool.submit(() -> {
        waiter.set(Thread.currentThread());
        IOException e = assertThrows(IOException.class, () -> HeicDecoder.decode(serialized, data, 0, false, strip));
        assertTrue(e instanceof InterruptedIOException, e.toString());
        return Thread.currentThread().isInterrupted();
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (waiter.get() == null || !HeicDecoder.isWaitingForNativeDecode(waiter.get())) {
        assertTrue(System.nanoTime() < deadline, "the second decode did not wait for the gate");
        Thread.sleep(1);
      }
      waiter.get().interrupt();
      assertTrue(second.get(60, TimeUnit.SECONDS), "the thread stays interrupted");
      assertFalse(first.isDone(), "the first decode is still in ImageIO");

      proceed.countDown();
      assertEquals(600, first.get(60, TimeUnit.SECONDS).getWidth());

      // ImageIO returns no image: the decode fails inside the gate and leaves it.
      MacApi failing = hooked((method, args) -> method.equals("cgImageSourceCreateThumbnailAtIndex") ? (Object) 0L : PROCEED);
      assertThrows(IOException.class, () -> HeicDecoder.decode(new HeicDecoder.Bound(failing, true), data, 0, false, strip));
      assertFalse(HeicDecoder.isInNativeDecode(), "a failed decode leaves the gate");
      assertEquals(600, pool.submit(() -> HeicDecoder.decode(serialized, data, 0, false, strip)).get(60, TimeUnit.SECONDS).getWidth());
    }
    finally {
      proceed.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void manySequentialDecodesReleaseNativeResources() throws IOException {
    byte[] data = Fixtures.bytes("alpha_sips.heic");
    for (int i = 0; i < 300; i++) {
      HeicDecoder.decode(data, 0);
      HeicDecoder.readInfo(data);
    }
    byte[] garbage = Fixtures.bytes("garbage.heic");
    for (int i = 0; i < 100; i++) {
      assertThrows(IOException.class, () -> HeicDecoder.decode(garbage, 0));
    }
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }
}
