package cn.yooss.heic.linux;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.HeifInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The libheif backend against the fixtures, with the expectations of the macOS decoder (sizes, orientation, alpha,
 * 10-bit, colors; libheif's HEVC decoder and YCbCr conversion differ slightly, hence tolerances). Runs wherever a
 * libheif with HEVC decoder is available ({@link TestLibheif}): Linux CI, and macOS with Homebrew's libheif, where the
 * images are also compared with ImageIO.framework's directly. {@code HeifBackendContractTest} checks the rest of the
 * contract on Linux.
 */
@EnabledIf("cn.yooss.heic.linux.TestLibheif#isAvailable")
class LibheifDecodingTest {
  private static HeifBackend backend() {
    return TestLibheif.backend();
  }

  /** Name, display size, alpha, bit depth as stored, number of top-level images. */
  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,        600,  400, false, 8,  1",
      "rgb_libheif.heic,     600,  400, false, 8,  1",
      "alpha_sips.heic,      400,  300, true,  8,  1",
      "alpha_libheif.heic,   400,  300, true,  8,  1",
      "rgb16_sips.heic,      512,  256, false, 10, 1",
      "ten_bit.heic,         512,  256, false, 10, 1",
      "exif3_apple.heic,     600,  400, false, 8,  1",
      "exif5_apple.heic,     400,  600, false, 8,  1",
      "exif6_apple.heic,     400,  600, false, 8,  1",
      "rot90_irot.heic,      400,  600, false, 8,  1",
      "fliph_imir.heic,      600,  400, false, 8,  1",
      "grid_libheif.heic,    600,  400, false, 8,  1",
      "multi.heic,           600,  400, false, 8,  2",
      "seq.heics,            600,  400, false, 8,  1",
      "bands_2000x1200.heic, 2000, 1200, false, 8, 1",
      "bands_exif6.heic,     1200, 2000, false, 8, 1",
  })
  void info(String name, int width, int height, boolean alpha, int bitDepth, int images) throws IOException {
    HeifImageInfo info = backend().readInfo(Fixtures.bytes(name));
    assertEquals(width + "x" + height, info.width() + "x" + info.height(), name);
    assertEquals(1, info.orientation(), "libheif applies irot/imir itself: " + info);
    assertEquals(alpha, info.hasAlpha(), name);
    assertEquals(bitDepth, info.bitDepth(), name);
    assertEquals(images, info.imageCount(), name);
    assertEquals(0, info.primaryIndex(), name);
    BufferedImage image = backend().decode(Fixtures.bytes(name), 0);
    assertEquals(width + "x" + height, image.getWidth() + "x" + image.getHeight(), name);
    assertEquals(alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB, image.getType(), name);
  }

  /** The same layouts as on macOS (HeifBackendContractTest): irot/imir are applied once, EXIF orientation not again. */
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "rgb_sips.heic     | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
      "exif3_apple.heic  | 600x400 TL=white TR=blue BL=green BR=red marker=BR",
      "exif5_apple.heic  | 400x600 TL=red TR=blue BL=green BR=white marker=TL",
      "exif6_apple.heic  | 400x600 TL=blue TR=red BL=white BR=green marker=TR",
      "rot90_irot.heic   | 400x600 TL=blue TR=red BL=white BR=green marker=TR",
      "fliph_imir.heic   | 600x400 TL=green TR=red BL=white BR=blue marker=TR",
      "grid_libheif.heic | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
      "seq.heics         | 600x400 TL=red TR=green BL=blue BR=white marker=TL",
  })
  void orientation(String name, String layout) throws IOException {
    assertEquals(layout, Fixtures.layout(backend().decode(Fixtures.bytes(name), 0)));
  }

  /** macOS ImageIO.framework's output of two fixtures, saved as PNG: libheif must agree within codec tolerance. */
  @ParameterizedTest
  @ValueSource(strings = {"exif6_apple.heic", "grid_libheif.heic"})
  void matchesTheMacOsDecoder(String name) throws IOException {
    double mean = Fixtures.meanDifference(backend().decode(Fixtures.bytes(name), 0), Fixtures.png(name + ".out.png"));
    assertTrue(mean < 2.0, "mean difference to macOS " + mean);
  }

  /** On a Mac with Homebrew's libheif: every fixture against ImageIO.framework, pixel by pixel on average. */
  @ParameterizedTest
  @ValueSource(strings = {"rgb_sips.heic", "rgb_libheif.heic", "alpha_sips.heic", "alpha_libheif.heic", "rgb16_sips.heic",
      "ten_bit.heic", "exif3_apple.heic", "exif5_apple.heic", "exif6_apple.heic", "rot90_irot.heic", "fliph_imir.heic",
      "grid_libheif.heic", "multi.heic", "seq.heics", "bands_exif6.heic"})
  void matchesImageIoFramework(String name) throws IOException {
    assumeTrue(HeifBackends.Os.current() == HeifBackends.Os.MAC, "needs macOS");
    HeifBackend mac = HeifBackends.create(HeifBackends.Os.MAC);
    byte[] data = Fixtures.bytes(name);
    BufferedImage expected = mac.decode(data, 0);
    BufferedImage actual = backend().decode(data, 0);
    assertEquals(expected.getType(), actual.getType(), name);
    double mean = Fixtures.meanDifference(actual, expected);
    assertTrue(mean < 2.0, name + ": mean difference to ImageIO.framework " + mean);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb_sips.heic", "rgb_libheif.heic", "grid_libheif.heic", "multi.heic", "seq.heics"})
  void colors(String name) throws IOException {
    double mean = Fixtures.meanDifference(backend().decode(Fixtures.bytes(name), 0), Fixtures.png("rgb.png"));
    assertTrue(mean < 2.5, "mean difference " + mean);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb16_sips.heic", "ten_bit.heic"})
  void tenBit(String name) throws IOException {
    double mean = Fixtures.meanDifference(backend().decode(Fixtures.bytes(name), 0), Fixtures.png("rgb16.png"));
    assertTrue(mean < 1.5, "mean difference to the 16-bit PNG source " + mean);
  }

  @ParameterizedTest
  @ValueSource(strings = {"alpha_sips.heic", "alpha_libheif.heic"})
  void straightAlpha(String name) throws IOException {
    BufferedImage image = backend().decode(Fixtures.bytes(name), 0);
    int[] raw = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    int w = image.getWidth();
    assertEquals(0, raw[150 * w + 50] >>> 24);
    assertTrue(Math.abs((raw[150 * w + 150] >>> 24) - 128) <= 3, Integer.toHexString(raw[150 * w + 150]));
    assertTrue(((raw[150 * w + 150] >> 16) & 0xFF) > 245, "red at alpha 128 is not darkened: " + Integer.toHexString(raw[150 * w + 150]));
    assertTrue(Math.abs((raw[150 * w + 350] >>> 24) - 64) <= 3, Integer.toHexString(raw[150 * w + 350]));
    double mean = Fixtures.meanDifference(image, Fixtures.png("alpha.png"));
    assertTrue(mean < 3.0, "mean difference " + mean);
  }

  /** Downscaled while reading the plane: exact sizes, never upscaled, layout kept. */
  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,        150,  150, 100",
      "exif6_apple.heic,     150,  100, 150",
      "grid_libheif.heic,    300,  300, 200",
      "bands_2000x1200.heic, 500,  500, 300",
      "bands_exif6.heic,     1000, 600, 1000",
      "bands_2000x1200.heic, 7,    7,   4",
      "rgb_sips.heic,        600,  600, 400",
      "rgb_sips.heic,        5000, 600, 400",
  })
  void downscale(String name, int maxPixelSize, int width, int height) throws IOException {
    BufferedImage image = backend().decode(Fixtures.bytes(name), maxPixelSize);
    assertEquals(width + "x" + height, image.getWidth() + "x" + image.getHeight(), name + " at " + maxPixelSize);
  }

  @Test
  void downscaledImagesKeepLayoutAndColors() throws IOException {
    assertEquals("200x300 TL=blue TR=red BL=white BR=green marker=TR",
                 Fixtures.layout(backend().decode(Fixtures.bytes("exif6_apple.heic"), 300)));
    BufferedImage small = backend().decode(Fixtures.bytes("rgb_sips.heic"), 60); // 60x40, factor 10 box filter
    assertEquals("60x40 TL=red TR=green BL=blue BR=white marker=none", Fixtures.layout(small));
    BufferedImage alpha = backend().decode(Fixtures.bytes("alpha_sips.heic"), 100); // 100x75, factor 4
    assertEquals(BufferedImage.TYPE_INT_ARGB, alpha.getType());
    int redAt128 = alpha.getRGB(37, 37);
    assertTrue(Math.abs((redAt128 >>> 24) - 128) <= 4 && ((redAt128 >> 16) & 0xFF) > 240, Integer.toHexString(redAt128));
  }

  @Test
  void thumbnailsWithoutEmbeddedThumbnail() throws IOException {
    LibheifHeifBackend backend = TestLibheif.backend();
    assertEquals("primary", decoder().thumbnailChoice(Fixtures.bytes("exif6_apple.heic"), 64));
    BufferedImage portrait = backend.decodeThumbnail(Fixtures.bytes("exif6_apple.heic"), 64);
    assertEquals("43x64", portrait.getWidth() + "x" + portrait.getHeight());
    BufferedImage alpha = backend.decodeThumbnail(Fixtures.bytes("alpha_sips.heic"), 32);
    assertEquals(BufferedImage.TYPE_INT_ARGB, alpha.getType());
    assertEquals("32x24", alpha.getWidth() + "x" + alpha.getHeight());
  }

  @Test
  void rejectsOtherFormatsAndBrokenFiles() throws IOException {
    HeifBackend backend = backend();
    assertEquals(HeifInput.NOT_HEIF, assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb.png"), 0)).getMessage());
    assertEquals(HeifInput.NOT_HEIF, assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb.avif"), 0)).getMessage());
    assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("header_only.heic"), 0));
    byte[] data = Fixtures.bytes("rgb_libheif.heic");
    for (int i = 40; i < data.length; i++) data[i] = (byte) (i * 31 + 7); // keep ftyp, destroy the rest
    IOException e = assertThrows(IOException.class, () -> backend.decode(data, 0));
    assertFalse(e.getMessage().isEmpty());
  }

  /** The decoder reads HEIF data that passed HeifInput: libheif's own errors surface as LibheifException. */
  @Test
  void libheifErrorsBecomeIOExceptions() {
    byte[] full = Fixtures.bytes("rgb_libheif.heic");
    byte[] ftypOnly = Arrays.copyOf(full, 32);
    LibheifException e = assertThrows(LibheifException.class, () -> decoder().decode(ftypOnly, 0, false));
    assertTrue(e.getMessage().startsWith("heif_context_read_from_memory_without_copy failed: "), e.getMessage());
  }

  private static LibheifDecoder decoder() {
    return new LibheifDecoder(TestLibheif.backend().library());
  }
}
