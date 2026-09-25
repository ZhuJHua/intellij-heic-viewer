package cn.yooss.heic.win;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackends;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A photo-like HEIC in Display P3: a grid of 512-pixel tiles with an ICC profile ({@code colr prof}), as iPhones write
 * them. {@code p3_grid_sips.heic} was made on macOS 26 from {@code p3_grid_source.png} (the quadrant fixture scaled to
 * 1152x768 with {@code sips -z 768 1152}): {@code sips -m "/System/Library/ColorSync/Profiles/Display P3.icc"} (pixels
 * converted to Display P3 and the profile attached), then {@code sips -s format heic}. Decoded with the system decoder
 * and converted to sRGB, it must match the sRGB source: on Windows this checks the ICC path of the backend
 * ({@code IWICColorContext} of type profile, {@code PixelPipeline.convertToSrgb}); on macOS it checks the fixture.
 */
class DisplayP3Test {
  private static byte[] resource(String name) {
    try (InputStream in = DisplayP3Test.class.getResourceAsStream(name)) {
      assertNotNull(in, name);
      return in.readAllBytes();
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void displayP3GridIsConvertedToSrgb() throws IOException {
    HeifBackend backend = HeifBackends.current();
    assumeTrue(backend.status().isAvailable(), "no system decoder");
    BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(resource("p3_grid_source.png")));
    BufferedImage image = backend.decode(resource("p3_grid_sips.heic"), 0);
    assertEquals("1152x768 TL=red TR=green BL=blue BR=white marker=TL", Fixtures.layout(image));
    double mean = Fixtures.meanDifference(image, source);
    assertTrue(mean < 3.0, backend.id() + ": mean difference to the sRGB source " + mean);
  }

  /** Without the conversion the Display P3 values would be far off: the check above can tell. */
  @Test
  void theProfileMatters() throws IOException {
    assumeTrue(HeifBackends.Os.current() == HeifBackends.Os.WINDOWS && HeifBackends.current().status().isAvailable(),
               "the Windows decoder is not available");
    WicDecoder decoder = ((WicHeifBackend) HeifBackends.current()).decoder();
    byte[] data = resource("p3_grid_sips.heic");
    try (WicDecoder.Session session = new WicDecoder.Session(decoder.api())) {
      WicDecoder.Opened opened = session.open(data, true);
      byte[] icc = session.iccProfile(opened.frame);
      assertNotNull(icc, "the HEIF decoder hands out the ICC profile");
      assertTrue(!IccProfiles.isSrgb(icc), "Display P3 is not sRGB");
      BufferedImage raw = session.render(opened, 0, cn.yooss.heic.backend.PixelPipeline.STRIP_PIXELS);
      BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(resource("p3_grid_source.png")));
      double unconverted = Fixtures.meanDifference(raw, source);
      assertTrue(unconverted > 5, "P3 values read as sRGB differ: " + unconverted);
    }
  }
}
