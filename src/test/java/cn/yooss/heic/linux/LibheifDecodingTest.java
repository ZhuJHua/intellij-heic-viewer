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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
      "grid_libheif.heic", "multi.heic", "seq.heics", "bands_exif6.heic", "icc_wide.heic", "thumb_irot.heic"})
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
    // The edge between the transparent column (black underneath) and the red one is not darkened (alpha-weighted).
    int edge = backend().decode(Fixtures.bytes("alpha_libheif.heic"), 90).getRGB(22, 34); // 90x68, factor 4 + bilinear
    assertTrue(Math.abs((edge >>> 24) - 64) <= 16 && ((edge >> 16) & 0xFF) >= 220, Integer.toHexString(edge));
  }

  /**
   * An embedded ICC profile (a wide-gamut space) is converted to sRGB. Expected: ImageIO.framework's result on macOS 26
   * (ColorSync); without the conversion the patches would keep their source values (200, 60, 60), (60, 180, 80), ...
   */
  @Test
  void iccProfileIsConvertedToSrgb() throws IOException {
    BufferedImage image = backend().decode(Fixtures.bytes("icc_wide.heic"), 0);
    assertColor(219, 39, 48, image.getRGB(100, 75));
    assertColor(0, 185, 62, image.getRGB(300, 75));
    assertColor(62, 91, 207, image.getRGB(100, 225));
    assertColor(129, 129, 129, image.getRGB(300, 225));
    // also when downscaled (converted after scaling)
    assertColor(219, 39, 48, backend().decode(Fixtures.bytes("icc_wide.heic"), 40).getRGB(10, 7));
  }

  private static void assertColor(int r, int g, int b, int argb) {
    String message = String.format("expected (%d,%d,%d), was (%d,%d,%d)", r, g, b, (argb >> 16) & 255, (argb >> 8) & 255, argb & 255);
    assertTrue(Math.abs(((argb >> 16) & 255) - r) <= 4 && Math.abs(((argb >> 8) & 255) - g) <= 4 && Math.abs((argb & 255) - b) <= 4,
               message);
  }

  /** thumb_irot.heic has irot (portrait 400x600) and a 64x96 thumbnail, used when it is at least as large as requested. */
  @Test
  void embeddedThumbnail() throws IOException {
    byte[] data = Fixtures.bytes("thumb_irot.heic");
    assertEquals("thumbnail 64x96", decoder().thumbnailChoice(data, 64));
    assertEquals("thumbnail 64x96", decoder().thumbnailChoice(data, 96));
    assertEquals("primary", decoder().thumbnailChoice(data, 97));
    BufferedImage small = backend().decodeThumbnail(data, 64);
    assertEquals("43x64", small.getWidth() + "x" + small.getHeight());
    String quadrants = "TL=blue TR=red BL=white BR=green"; // like rot90_irot.heic: the thumbnail's irot is applied
    assertTrue(Fixtures.layout(small).contains(quadrants), Fixtures.layout(small));
    BufferedImage large = backend().decodeThumbnail(data, 200);
    assertEquals("133x200", large.getWidth() + "x" + large.getHeight());
    assertTrue(Fixtures.layout(large).contains(quadrants), Fixtures.layout(large));
    // decode() never uses the thumbnail
    assertEquals("400x600 TL=blue TR=red BL=white BR=green marker=TR", Fixtures.layout(backend().decode(data, 0)));
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

  /**
   * A primary image above the decode limit ({@link LibheifDecoder#MAX_DECODE_SIDE} squared pixels, made small here) is
   * not decoded: libheif would build it at full size in native memory. The declared size is checked in Java, and libheif
   * checks the image it builds, so that a small declared size ({@code ispe}) cannot get around the limit. Reading the
   * size and embedded thumbnails are not limited.
   */
  @Test
  void imagesAboveTheDecodeLimitAreNotDecoded() throws IOException {
    Libheif lib = TestLibheif.backend().library();
    byte[] grid = Fixtures.bytes("grid_libheif.heic"); // 600x400, a grid of 128x128 tiles
    IOException tooLarge = assertThrows(IOException.class, () -> new LibheifDecoder(lib, 256).decode(grid, 0, false));
    assertTrue(tooLarge.getMessage().contains("too large to decode"), tooLarge.getMessage());
    assertEquals("600x400", size(new LibheifDecoder(lib, 256).readInfo(grid)));
    BufferedImage fits = new LibheifDecoder(lib, 1024).decode(grid, 0, false);
    assertEquals("600x400", fits.getWidth() + "x" + fits.getHeight());
    byte[] withThumbnail = Fixtures.bytes("thumb_irot.heic"); // 400x600 with a 64x96 thumbnail
    assertEquals("43x64", size(new LibheifDecoder(lib, 16).decode(withThumbnail, 64, true)));

    assumeTrue(lib.canLimitDecodeSize(), "libheif " + lib.version() + " has no heif_context_set_maximum_image_size_limit");
    byte[] claimsSmall = TestLibheif.withIspe(grid, 600, 400, 64, 64); // the grid says 64x64, its canvas is still 600x400
    assertEquals("64x64", size(new LibheifDecoder(lib, 256).readInfo(claimsSmall)));
    LibheifException limited = assertThrows(LibheifException.class, () -> new LibheifDecoder(lib, 256).decode(claimsSmall, 0, false));
    assertEquals(6, limited.code(), limited.getMessage()); // heif_error_Memory_allocation_error
    assertEquals(1000, limited.subcode(), limited.getMessage()); // heif_suberror_Security_limit_exceeded
    try {
      new LibheifDecoder(lib, 1024).decode(claimsSmall, 0, false); // decodes with some versions, others refuse the ispe
    }
    catch (LibheifException e) {
      assertNotEquals(1000, e.subcode(), "not the limit: " + e.getMessage());
    }
  }

  private static String size(BufferedImage image) {
    return image.getWidth() + "x" + image.getHeight();
  }

  private static String size(HeifImageInfo info) {
    return info.width() + "x" + info.height();
  }

  private static LibheifDecoder decoder() {
    return new LibheifDecoder(TestLibheif.backend().library());
  }
}
