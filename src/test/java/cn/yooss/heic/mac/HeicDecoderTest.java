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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

  /** 2000x1200 = 2.4 MP: rendered in 3 strips of 524 rows (the strip budget is 2^20 pixels). */
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
    // rows right at the strip boundaries (523/524 and 1047/1048)
    assertPixel(0xFF000000 | BAND_COLORS[5], image.getRGB(1000, 523), 0, 6);
    assertPixel(0xFF000000 | BAND_COLORS[5], image.getRGB(1000, 524), 0, 6);
    assertPixel(0xFF000000 | BAND_COLORS[4], image.getRGB(1000, 1047), 0, 6);
    assertPixel(0xFF000000 | BAND_COLORS[4], image.getRGB(1000, 1048), 0, 6);
    assertPixel(0xFF000000, image.getRGB(20, 20), 0, 16); // marker
    double mean = Fixtures.meanDifference(image, Fixtures.png("bands.png"));
    assertTrue(mean < 3.0, "mean difference " + mean);
  }

  /** Cropped-strip rendering must be pixel-identical to drawing the whole image at once, for any strip height. */
  @ParameterizedTest
  @ValueSource(strings = {"bands_2000x1200.heic", "bands_exif6.heic", "alpha_sips.heic", "exif5_apple.heic"})
  void stripHeightDoesNotChangePixels(String name) throws IOException {
    byte[] data = Fixtures.bytes(name);
    int[] single = pixels(HeicDecoder.decode(data, 0, false, Integer.MAX_VALUE));
    for (int stripPixels : new int[]{1, 7 * 2000, 100_000, 1 << 20}) {
      assertArrayEquals(single, pixels(HeicDecoder.decode(data, 0, false, stripPixels)), "strip pixels " + stripPixels);
    }
  }

  /** Orientation 6 (rotate 90 degrees clockwise) on an image that is rendered in several strips. */
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

  @Test
  void concurrentDecodesAreIdentical() throws Exception {
    String[] names = {"rgb_sips.heic", "grid_libheif.heic", "alpha_sips.heic", "exif6_apple.heic"};
    List<int[]> expected = new ArrayList<>();
    for (String name : names) expected.add(pixels(HeicDecoder.decode(Fixtures.bytes(name), 0)));

    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Future<int[]>> futures = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        String name = names[i % names.length];
        futures.add(pool.submit(() -> pixels(HeicDecoder.decode(Fixtures.bytes(name), 0))));
      }
      for (int i = 0; i < futures.size(); i++) {
        assertArrayEquals(expected.get(i % names.length), futures.get(i).get(60, TimeUnit.SECONDS), names[i % names.length]);
      }
    }
    finally {
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
