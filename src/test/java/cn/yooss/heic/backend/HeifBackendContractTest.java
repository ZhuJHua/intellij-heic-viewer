package cn.yooss.heic.backend;

import cn.yooss.heic.Fixtures;
import org.junit.jupiter.api.Test;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@link HeifBackend} contract, checked against the backend of the OS the tests run on
 * ({@link HeifBackends#current()}): macOS ImageIO.framework, Windows WIC or Linux libheif. Every backend must produce
 * the same images (sizes, orientation, alpha, colors within codec tolerances) from the same fixtures.
 * <p>
 * Tests that decode are skipped where the system decoder is unavailable. {@code HEIC_EXPECT_BACKEND} (system property
 * {@code heic.test.expectBackend}) names the status the backend must report: {@code available}, or the
 * {@link HeifBackendStatus.Reason} name (e.g. {@code LINUX_LIBHEIF_MISSING} without libheif).
 */
class HeifBackendContractTest {
  private static final HeifBackend BACKEND = HeifBackends.current();

  private static boolean available() {
    return BACKEND.status().isAvailable();
  }

  @Test
  void statusMatchesWhatThisEnvironmentExpects() {
    HeifBackendStatus status = BACKEND.status();
    System.out.println("HEIF backend " + BACKEND.id() + " (" + BACKEND.displayName() + "): " + status);
    String expected = System.getProperty("heic.test.expectBackend", "").trim();
    assumeFalse(expected.isEmpty(), "heic.test.expectBackend is not set");
    String actual = status.isAvailable() ? "available" : String.valueOf(status.reason());
    assertEquals(expected.toLowerCase(Locale.ROOT), actual.toLowerCase(Locale.ROOT), status.toString());
  }

  @Test
  void statusIsCachedAndConsistent() {
    HeifBackendStatus status = BACKEND.status();
    assertSame(status, BACKEND.status());
    assertEquals(status.isAvailable(), status.reason() == null);
    assertFalse(BACKEND.id().isEmpty());
    assertFalse(BACKEND.displayName().isEmpty());
    if (status.installUrl() != null || status.installCommand() != null) {
      assertTrue(status.isUserInstallable(), "install hints only for installable components: " + status);
    }
    // The banner and the notifications must accept what the backend offers (they ignore other URL schemes and
    // multi-line commands).
    if (status.installUrl() != null) assertTrue(HeifRemedies.isAllowedUrl(status.installUrl()), status.toString());
    if (status.installCommand() != null) assertTrue(HeifRemedies.isAllowedCommand(status.installCommand()), status.toString());
    if (!status.isAvailable()) assertNotNull(HeifRemedies.forStatus(status), status.toString());
  }

  @Test
  void unavailableBackendFailsWithIOException() {
    assumeFalse(available(), "the system decoder is available");
    byte[] heic = Fixtures.bytes("rgb_sips.heic");
    IOException e = assertThrows(IOException.class, () -> BACKEND.decode(heic, 0));
    assertTrue(e.getMessage().contains(String.valueOf(BACKEND.status().reason())), e.getMessage());
    assertThrows(IOException.class, () -> BACKEND.readInfo(heic));
  }

  /** Name, display size, alpha of every decodable fixture. */
  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,        600,  400, false",
      "rgb_libheif.heic,     600,  400, false",
      "alpha_sips.heic,      400,  300, true",
      "alpha_libheif.heic,   400,  300, true",
      "rgb16_sips.heic,      512,  256, false",
      "ten_bit.heic,         512,  256, false",
      "exif3_apple.heic,     600,  400, false",
      "exif5_apple.heic,     400,  600, false",
      "exif6_apple.heic,     400,  600, false",
      "rot90_irot.heic,      400,  600, false",
      "fliph_imir.heic,      600,  400, false",
      "grid_libheif.heic,    600,  400, false",
      "multi.heic,           600,  400, false",
      "seq.heics,            600,  400, false",
      "bands_2000x1200.heic, 2000, 1200, false",
      "bands_exif6.heic,     1200, 2000, false",
  })
  void infoAndFullDecode(String name, int width, int height, boolean alpha) throws IOException {
    assumeTrue(available(), "no system decoder");
    byte[] data = Fixtures.bytes(name);
    HeifImageInfo info = BACKEND.readInfo(data);
    assertEquals(width + "x" + height, info.width() + "x" + info.height(), "display size of " + name);
    assertEquals(alpha, info.hasAlpha(), "alpha of " + name);
    assertTrue(info.imageCount() >= 1);

    BufferedImage image = BACKEND.decode(data, 0);
    assertEquals(width + "x" + height, image.getWidth() + "x" + image.getHeight(), "decoded size of " + name);
    assertEquals(alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB, image.getType());
    assertFalse(image.isAlphaPremultiplied());
  }

  /** Expected layouts of the quadrant fixture after orientation. */
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
    assumeTrue(available(), "no system decoder");
    assertEquals(expectedLayout, Fixtures.layout(BACKEND.decode(Fixtures.bytes(name), 0)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb_sips.heic", "rgb_libheif.heic", "grid_libheif.heic", "multi.heic", "seq.heics"})
  void colorsMatchTheSourcePng(String name) throws IOException {
    assumeTrue(available(), "no system decoder");
    double mean = Fixtures.meanDifference(BACKEND.decode(Fixtures.bytes(name), 0), Fixtures.png("rgb.png"));
    // lossy HEVC with 4:2:0 chroma blurs the quadrant edges
    assertTrue(mean < 2.5, "mean difference " + mean);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb16_sips.heic", "ten_bit.heic"})
  void tenBitImagesDecodeToEightBitSrgb(String name) throws IOException {
    assumeTrue(available(), "no system decoder");
    double mean = Fixtures.meanDifference(BACKEND.decode(Fixtures.bytes(name), 0), Fixtures.png("rgb16.png"));
    assertTrue(mean < 1.5, "mean difference to the 16-bit PNG source " + mean);
  }

  /** Raw raster values (not getRGB) must be straight alpha. */
  @ParameterizedTest
  @ValueSource(strings = {"alpha_sips.heic", "alpha_libheif.heic"})
  void alphaIsStraight(String name) throws IOException {
    assumeTrue(available(), "no system decoder");
    BufferedImage image = BACKEND.decode(Fixtures.bytes(name), 0);
    int[] raw = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    int w = image.getWidth();
    assertPixel(0x00000000, raw[150 * w + 50], 0, 255);  // transparent (color undefined)
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

  /** maxPixelSize bounds the longer side (downscaled while or after decoding), keeps the aspect ratio, never upscales. */
  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,        150,  150, 100",
      "exif6_apple.heic,     150,  100, 150",
      "grid_libheif.heic,    300,  300, 200",
      "bands_2000x1200.heic, 500,  500, 300",
      "bands_exif6.heic,     1000, 600, 1000",
      "rgb_sips.heic,        600,  600, 400",
      "rgb_sips.heic,        5000, 600, 400",
  })
  void maxPixelSizeDownscales(String name, int maxPixelSize, int width, int height) throws IOException {
    assumeTrue(available(), "no system decoder");
    BufferedImage image = BACKEND.decode(Fixtures.bytes(name), maxPixelSize);
    assertTrue(Math.abs(image.getWidth() - width) <= 1 && Math.abs(image.getHeight() - height) <= 1,
               "expected about " + width + "x" + height + ", was " + image.getWidth() + "x" + image.getHeight());
    assertTrue(Math.max(image.getWidth(), image.getHeight()) <= Math.max(maxPixelSize, 3));
  }

  /**
   * Downscaling an image with alpha weights the colors by alpha: the color under the transparent pixels (black in HEIC
   * files) does not darken the edges.
   */
  @ParameterizedTest
  @ValueSource(strings = {"alpha_libheif.heic", "alpha_sips.heic"})
  void downscaledAlphaEdgesAreNotDarkened(String name) throws IOException {
    assumeTrue(available(), "no system decoder");
    BufferedImage image = BACKEND.decode(Fixtures.bytes(name), 90);
    assertEquals("90x68", image.getWidth() + "x" + image.getHeight());
    // x = 22 covers the source columns 97.8 to 102.2: half transparent, half red at alpha 128
    int edge = image.getRGB(22, 34);
    String message = name + " (" + BACKEND.id() + "): " + Integer.toHexString(edge);
    assertTrue(Math.abs((edge >>> 24) - 64) <= 16, message);
    // Alpha-weighted: red stays red (about 255).
    assertTrue(((edge >> 16) & 0xFF) >= 220, message);
    assertTrue(((edge >> 8) & 0xFF) <= 40 && (edge & 0xFF) <= 40, message);
  }

  @Test
  void downscaledImageKeepsTheLayout() throws IOException {
    assumeTrue(available(), "no system decoder");
    assertEquals("200x300 TL=blue TR=red BL=white BR=green marker=TR",
                 Fixtures.layout(BACKEND.decode(Fixtures.bytes("exif6_apple.heic"), 300)));
  }

  /** System decoders pick the codec by content: nothing but HEIF may reach them, whatever the file is called. */
  @ParameterizedTest
  @ValueSource(strings = {"png", "tiff", "bmp", "gif", "jpeg"})
  void otherFormatsAreRejectedBeforeTheSystemDecoder(String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(Fixtures.png("rgb.png"), format, out), format);
    byte[] data = out.toByteArray();
    assertEquals(HeifInput.NOT_HEIF, assertThrows(IOException.class, () -> BACKEND.readInfo(data)).getMessage());
    assertEquals(HeifInput.NOT_HEIF, assertThrows(IOException.class, () -> BACKEND.decode(data, 0)).getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb.avif", "alpha.avif", "rgb_sips.avif", "garbage.heic"})
  void avifAndGarbageAreRejected(String name) {
    IOException e = assertThrows(IOException.class, () -> BACKEND.decode(Fixtures.bytes(name), 0));
    assertEquals(HeifInput.NOT_HEIF, e.getMessage(), name);
  }

  @Test
  void invalidAndTruncatedFilesFailWithIOException() {
    assertThrows(IOException.class, () -> BACKEND.decode(Fixtures.bytes("header_only.heic"), 0));
    assertThrows(IOException.class, () -> BACKEND.decode(new byte[0], 0));
    assertThrows(IOException.class, () -> BACKEND.decode(null, 0));
    for (String name : new String[]{"rgb_sips.heic", "grid_libheif.heic", "multi.heic", "alpha_libheif.heic", "seq.heics"}) {
      byte[] full = Fixtures.bytes(name);
      for (int length : new int[]{16, full.length / 3, full.length / 2, full.length - 1}) {
        byte[] data = Arrays.copyOf(full, length);
        assertThrows(IOException.class, () -> BACKEND.decode(data, 0), name + " cut at " + length);
      }
    }
  }

  @Test
  void randomBytesAfterAValidHeaderFailWithIOException() {
    byte[] data = Fixtures.bytes("rgb_sips.heic");
    for (int i = 40; i < data.length; i++) data[i] = (byte) (i * 31 + 7); // keep ftyp, destroy the rest
    assertThrows(IOException.class, () -> BACKEND.decode(data, 0));
  }

  @Test
  void concurrentDecodesAreIdentical() throws Exception {
    assumeTrue(available(), "no system decoder");
    String[] names = {"rgb_sips.heic", "grid_libheif.heic", "alpha_sips.heic", "exif6_apple.heic"};
    List<int[]> expected = new ArrayList<>();
    for (String name : names) expected.add(pixels(BACKEND.decode(Fixtures.bytes(name), 0)));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<int[]>> futures = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        String name = names[i % names.length];
        futures.add(pool.submit(() -> pixels(BACKEND.decode(Fixtures.bytes(name), 0))));
      }
      for (int i = 0; i < futures.size(); i++) {
        int[] actual = futures.get(i).get(60, TimeUnit.SECONDS);
        assertNotNull(actual);
        assertArrayEquals(expected.get(i % names.length), actual, names[i % names.length]);
      }
    }
    finally {
      pool.shutdownNow();
    }
  }

  private static int[] pixels(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }
}
