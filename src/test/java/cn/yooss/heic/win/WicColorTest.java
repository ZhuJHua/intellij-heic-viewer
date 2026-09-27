package cn.yooss.heic.win;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.backend.PixelPipeline;
import cn.yooss.heic.win.jna.JnaWinApi;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The colors of the color fixtures against their sources, with the system decoder. On Windows with the HEIF Image
 * Extension and the HEVC Video Extension each fixture is decoded twice: as WIC decodes it (color fixes off) and as the
 * plugin decodes it (on); only the second must match. The pixel values and differences go to the test output. On
 * macOS the same comparisons check the fixtures and their references.
 */
@EnabledOnOs({OS.WINDOWS, OS.MAC})
class WicColorTest {
  private static final String HEADER = String.format(Locale.ROOT, "%-34s %-6s | %-44s | %-44s%n", "fixture", "fixes",
                                                     "as WIC decodes it: TL TR BL BR, mean difference",
                                                     "with the color fixes (the plugin)");
  private static boolean headerPrinted;

  /**
   * @param path        the HEIF resource
   * @param reference   its source PNG
   * @param orientation the EXIF orientation that turns the source into the displayed image
   * @param tolerance   the largest mean difference (per channel, 0-255) allowed with the color fixes
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
    // single 8-bit images: BT.601 full range (libheif, macOS), BT.709, limited range, 4:4:4, alpha, transformations
    "fixtures/rgb_sips.heic             | fixtures/rgb.png                    | 1 | 2.5",
    "fixtures/rgb_libheif.heic          | fixtures/rgb.png                    | 1 | 2.5",
    "cn/yooss/heic/win/rgb_bt709.heic   | fixtures/rgb.png                    | 1 | 2.5",
    "cn/yooss/heic/win/rgb_limited.heic | fixtures/rgb.png                    | 1 | 2.5",
    "cn/yooss/heic/win/rgb_444.heic     | fixtures/rgb.png                    | 1 | 2.5",
    "fixtures/multi.heic                | fixtures/rgb.png                    | 1 | 2.5",
    "fixtures/seq.heics                 | fixtures/rgb.png                    | 1 | 2.5",
    "fixtures/alpha_sips.heic           | fixtures/alpha.png                  | 1 | 3.0",
    "fixtures/alpha_libheif.heic        | fixtures/alpha.png                  | 1 | 3.0",
    "fixtures/exif3_apple.heic          | fixtures/rgb.png                    | 3 | 2.5",
    "fixtures/exif5_apple.heic          | fixtures/rgb.png                    | 5 | 2.5",
    "fixtures/exif6_apple.heic          | fixtures/rgb.png                    | 6 | 2.5",
    "fixtures/rot90_irot.heic           | fixtures/rgb.png                    | 6 | 2.5",
    "fixtures/fliph_imir.heic           | fixtures/rgb.png                    | 2 | 2.5",
    "fixtures/thumb_irot.heic           | fixtures/rgb.png                    | 6 | 2.5",
    // smooth tones: macOS's single image (nclx 2, 2, 6, 1) and a grid with the BT.709 transfer curve (nclx 1, 1, 6, 1)
    "cn/yooss/heic/win/smooth_sips.heic     | cn/yooss/heic/win/smooth.png | 1 | 3.0",
    "cn/yooss/heic/win/smooth_grid_tc1.heic | cn/yooss/heic/win/smooth.png | 1 | 3.0",
    // grids and 10-bit images
    "fixtures/grid_libheif.heic         | fixtures/rgb.png                    | 1 | 2.5",
    "fixtures/bands_2000x1200.heic      | fixtures/bands.png                  | 1 | 3.0",
    "cn/yooss/heic/win/p3_grid_sips.heic | cn/yooss/heic/win/p3_grid_source.png | 1 | 3.0",
    "fixtures/ten_bit.heic              | fixtures/rgb16.png                  | 1 | 1.5",
    "fixtures/rgb16_sips.heic           | fixtures/rgb16.png                  | 1 | 1.5",
  })
  void colorsMatchTheSource(String path, String reference, int orientation, double tolerance) throws IOException {
    HeifBackend backend = HeifBackends.current();
    assumeTrue(backend.status().isAvailable(), "no system decoder");
    byte[] data = SingleImageGridTest.resource(path);
    BufferedImage expected = PixelPipeline.applyOrientation(
      ImageIO.read(new ByteArrayInputStream(SingleImageGridTest.resource(reference))), orientation);
    String name = path.substring(path.lastIndexOf('/') + 1);
    // which color fixes apply: g = SingleImageGrid, t = NclxTransfer
    byte[] srgb = NclxTransfer.asSrgb(data);
    String wrapped = (SingleImageGrid.wrap(srgb != null ? srgb : data) != null ? "g" : "") + (srgb != null ? "t" : "");
    if (wrapped.isEmpty()) wrapped = "-";

    if (backend.id().equals("windows-wic")) {
      WinApi api = new JnaWinApi();
      Result asIs = decode(new WicDecoder(api, false), data, expected);
      Result plugin = decode(new WicDecoder(api, true), data, expected);
      report(String.format(Locale.ROOT, "%-34s %-6s | %-44s | %-44s%n", name, wrapped, asIs, plugin));
      assertGeometry(expected, plugin.image, reference, name);
      assertTrue(plugin.mean < tolerance, name + ": mean difference " + plugin.mean + " (as WIC decodes it: " + asIs.mean + ")");
    }
    else {
      Result result = decode(backend, data, expected);
      report(String.format(Locale.ROOT, "%-34s %-6s | %-44s%n", name, wrapped, result));
      assertGeometry(expected, result.image, reference, name);
      assertTrue(result.mean < tolerance, name + ": mean difference " + result.mean);
    }
  }

  /** Size, and for the quadrant fixture the quadrants and the corner marker (orientation). */
  private static void assertGeometry(BufferedImage expected, BufferedImage image, String reference, String name) {
    if (reference.endsWith("/rgb.png")) assertEquals(Fixtures.layout(expected), Fixtures.layout(image), name);
    else assertEquals(expected.getWidth() + "x" + expected.getHeight(), image.getWidth() + "x" + image.getHeight(), name);
  }

  private interface Decoder {
    BufferedImage decode(byte[] data) throws IOException;
  }

  private static Result decode(WicDecoder decoder, byte[] data, BufferedImage expected) throws IOException {
    return compare(d -> decoder.decode(d, 0, true, PixelPipeline.STRIP_PIXELS), data, expected);
  }

  private static Result decode(HeifBackend backend, byte[] data, BufferedImage expected) throws IOException {
    return compare(d -> backend.decode(d, 0), data, expected);
  }

  private static Result compare(Decoder decoder, byte[] data, BufferedImage expected) throws IOException {
    BufferedImage image = decoder.decode(data);
    return new Result(image, Fixtures.meanDifference(image, expected));
  }

  private static final class Result {
    final BufferedImage image;
    final double mean;

    Result(BufferedImage image, double mean) {
      this.image = image;
      this.mean = mean;
    }

    @Override
    public String toString() {
      int w = image.getWidth(), h = image.getHeight();
      return String.format(Locale.ROOT, "%s %s %s %s %5.2f", hex(w / 4, h / 4), hex(3 * w / 4, h / 4),
                           hex(w / 4, 3 * h / 4), hex(3 * w / 4, 3 * h / 4), mean);
    }

    private String hex(int x, int y) {
      return String.format(Locale.ROOT, "%06X", image.getRGB(x, y) & 0xFFFFFF);
    }
  }

  private static synchronized void report(String line) {
    if (!headerPrinted) {
      headerPrinted = true;
      System.out.println("Backend: " + HeifBackends.current().status());
      System.out.print(HEADER);
    }
    System.out.print(line);
  }
}
