package cn.yooss.heic.thumbnail;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.mac.HeicDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailRendererTest {
  /** The quadrant fixture (600x400: TL red, TR green, BL blue, BR white) in a 32 px box: rect (0,5) 32x21. */
  @Test
  void landscapeImageIsCenteredOnTransparentBackground() {
    BufferedImage icon = ThumbnailRenderer.render(Fixtures.png("rgb.png"), 32, false);
    assertEquals(32, icon.getWidth());
    assertEquals(32, icon.getHeight());
    for (int x = 0; x < 32; x++) {
      for (int y : new int[]{0, 4, 26, 31}) {
        assertEquals(0, icon.getRGB(x, y) >>> 24, "padding row " + y + " must be transparent");
      }
      for (int y : new int[]{5, 15, 25}) {
        assertEquals(255, icon.getRGB(x, y) >>> 24, "image row " + y + " must be opaque");
      }
    }
    assertEquals("red", Fixtures.colorName(icon.getRGB(8, 10)));
    assertEquals("green", Fixtures.colorName(icon.getRGB(24, 10)));
    assertEquals("blue", Fixtures.colorName(icon.getRGB(8, 21)));
    assertEquals("white", Fixtures.colorName(icon.getRGB(24, 21)));
  }

  @Test
  void borderOutlinesTheImageOnly() {
    BufferedImage plain = ThumbnailRenderer.render(Fixtures.png("rgb.png"), 32, false);
    BufferedImage framed = ThumbnailRenderer.render(Fixtures.png("rgb.png"), 32, true);
    // Top edge of the image (y = 5) in the red quadrant: blended with the gray border, still opaque.
    int edge = framed.getRGB(8, 5);
    assertEquals(255, edge >>> 24);
    assertEquals("red", Fixtures.colorName(plain.getRGB(8, 5)));
    int green = (edge >> 8) & 255;
    assertTrue(green > 30 && green < 90, "gray blended into red: " + Integer.toHexString(edge));
    // Interior and padding are unchanged.
    assertEquals(plain.getRGB(8, 10), framed.getRGB(8, 10));
    assertEquals(0, framed.getRGB(8, 4) >>> 24);
    assertEquals(0, framed.getRGB(8, 26) >>> 24);
    // Left, right and bottom edges as well.
    assertTrue(((framed.getRGB(0, 10) >> 8) & 255) > 30);
    assertTrue((framed.getRGB(31, 21) & 255) < 240, "white blended with gray: " + Integer.toHexString(framed.getRGB(31, 21)));
    assertTrue((framed.getRGB(24, 25) & 255) < 240);
  }

  /** alpha.png (400x300): four columns: transparent, red@128, blue, green@64. In a 32 px box: rect (0,4) 32x24. */
  @Test
  void transparencyIsPreserved() {
    BufferedImage icon = ThumbnailRenderer.render(Fixtures.png("alpha.png"), 32, true);
    assertTrue((icon.getRGB(4, 16) >>> 24) < 20, "transparent column");
    int red = icon.getRGB(12, 16);
    assertTrue(Math.abs((red >>> 24) - 128) <= 3, "red at alpha 128: " + Integer.toHexString(red));
    assertTrue(((red >> 16) & 255) > 240 && ((red >> 8) & 255) < 20, "straight red: " + Integer.toHexString(red));
    assertEquals("blue", Fixtures.colorName(icon.getRGB(20, 16)));
    int green = icon.getRGB(28, 16);
    assertTrue(Math.abs((green >>> 24) - 64) <= 3, "green at alpha 64: " + Integer.toHexString(green));
    assertEquals(0, icon.getRGB(16, 1) >>> 24, "padding");
  }

  @Test
  void downscalingAveragesInsteadOfAliasing() {
    // 1-pixel black/white checkerboard: any correct downscale is a uniform mid gray.
    BufferedImage checker = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++) {
      for (int x = 0; x < 64; x++) {
        checker.setRGB(x, y, (x + y) % 2 == 0 ? 0xFFFFFF : 0x000000);
      }
    }
    for (int size : new int[]{32, 16, 11}) {
      BufferedImage scaled = ThumbnailRenderer.scale(checker, size, size);
      assertEquals(size, scaled.getWidth());
      for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
          int gray = scaled.getRGB(x, y) & 255;
          assertTrue(gray > 100 && gray < 155, size + ": pixel " + x + "," + y + " = " + gray);
        }
      }
    }
  }

  @Test
  void scaleProducesExactSizeUpAndDown() {
    BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
    BufferedImage up = ThumbnailRenderer.scale(source, 32, 16);
    assertEquals(32, up.getWidth());
    assertEquals(16, up.getHeight());
    BufferedImage same = ThumbnailRenderer.scale(source, 4, 2);
    assertEquals(4, same.getWidth());
    BufferedImage odd = ThumbnailRenderer.scale(new BufferedImage(601, 399, BufferedImage.TYPE_INT_RGB), 32, 21);
    assertEquals(new Rectangle(0, 0, 32, 21), new Rectangle(0, 0, odd.getWidth(), odd.getHeight()));
    assertThrows(IllegalArgumentException.class, () -> ThumbnailRenderer.scale(source, 0, 1));
  }

  @Test
  void singleScaleIconIsAPlainImage() {
    Image icon = ThumbnailRenderer.renderIcon(Fixtures.png("rgb.png"), 16, new double[]{1.0});
    BufferedImage image = assertInstanceOf(BufferedImage.class, icon);
    assertEquals(16, image.getWidth());
    assertEquals(16, image.getHeight());
  }

  @Test
  void hiDpiIconHasOneVariantPerScaleAndLogicalBaseSize() {
    Image icon = ThumbnailRenderer.renderIcon(Fixtures.png("rgb.png"), 16, new double[]{1.0, 2.0});
    MultiResolutionImage multi = assertInstanceOf(MultiResolutionImage.class, icon);
    List<Image> variants = multi.getResolutionVariants();
    assertEquals(2, variants.size());
    assertEquals(16, variants.get(0).getWidth(null));
    assertEquals(32, variants.get(1).getWidth(null));
    assertEquals(16, icon.getWidth(null), "logical size = base variant");
    assertEquals(16, icon.getHeight(null));
    assertEquals(32, multi.getResolutionVariant(32, 32).getWidth(null));

    assertThrows(IllegalArgumentException.class, () -> ThumbnailRenderer.renderIcon(Fixtures.png("rgb.png"), 16, new double[]{2.0}));
    assertThrows(IllegalArgumentException.class, () -> ThumbnailRenderer.renderIcon(Fixtures.png("rgb.png"), 16, new double[]{1.0, 1.0}));
  }

  /** On a 2x Graphics (Retina), Java2D paints the 32 px variant 1:1 instead of upscaling the 16 px base image. */
  @Test
  void hiDpiVariantIsPaintedOnScaledGraphics() {
    Image icon = ThumbnailRenderer.renderIcon(Fixtures.png("rgb.png"), 16, new double[]{1.0, 2.0});
    BufferedImage hiRes = (BufferedImage) ((MultiResolutionImage) icon).getResolutionVariants().get(1);
    BufferedImage lowRes = (BufferedImage) ((MultiResolutionImage) icon).getResolutionVariants().get(0);

    BufferedImage retina = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = retina.createGraphics();
    g.scale(2, 2);
    g.drawImage(icon, 0, 0, 16, 16, null); // what StartupUiUtil.drawImage does for a JBImageIcon
    g.dispose();
    assertSamePixels(hiRes, retina);

    BufferedImage regular = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    g = regular.createGraphics();
    g.drawImage(icon, 0, 0, null);
    g.dispose();
    assertSamePixels(lowRes, regular);
  }

  /**
   * The icon handed to the platform must not reference any class of this plugin, otherwise icons cached by the
   * platform (project view nodes, LastComputedIconCache, IconDeferrer) would keep the plugin class loader alive after
   * a dynamic unload. Everything in the image is a JDK class (loaded by the boot class loader).
   */
  @Test
  void iconImageUsesJdkClassesOnly() {
    Image icon = ThumbnailRenderer.renderIcon(Fixtures.png("alpha.png"), 16, new double[]{1.0, 2.0});
    assertInstanceOf(BaseMultiResolutionImage.class, icon);
    assertNull(icon.getClass().getClassLoader());
    for (Image variant : ((MultiResolutionImage) icon).getResolutionVariants()) {
      assertNull(variant.getClass().getClassLoader(), variant.getClass().getName());
      BufferedImage buffered = (BufferedImage) variant;
      assertNull(buffered.getColorModel().getClass().getClassLoader());
      assertNull(buffered.getRaster().getClass().getClassLoader());
    }
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void orientedHeicThumbnail() throws IOException {
    // exif6_apple.heic is displayed as 400x600: TL=blue TR=red BL=white BR=green.
    ThumbnailKey key = ThumbnailKey.of("file:///exif6_apple.heic", 0, 1, 16, 2.0);
    BufferedImage decoded = HeicDecoder.decodeThumbnail(Fixtures.bytes("exif6_apple.heic"), key.decodeSize());
    assertTrue(Math.max(decoded.getWidth(), decoded.getHeight()) <= 64);
    Image icon = ThumbnailRenderer.renderIcon(decoded, key.iconSize(), key.scales());
    BufferedImage variant = (BufferedImage) ((MultiResolutionImage) icon).getResolutionVariants().get(1);
    // Portrait in a 32 px box: rect (5,0) 21x32.
    assertEquals(0, variant.getRGB(2, 16) >>> 24, "left padding");
    assertEquals(0, variant.getRGB(29, 16) >>> 24, "right padding");
    assertEquals("blue", Fixtures.colorName(variant.getRGB(10, 8)));
    assertEquals("red", Fixtures.colorName(variant.getRGB(20, 8)));
    assertEquals("white", Fixtures.colorName(variant.getRGB(10, 24)));
    assertEquals("green", Fixtures.colorName(variant.getRGB(20, 24)));
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void heicWithAlphaKeepsTransparency() throws IOException {
    BufferedImage decoded = HeicDecoder.decodeThumbnail(Fixtures.bytes("alpha_sips.heic"), 64);
    BufferedImage icon = ThumbnailRenderer.render(decoded, 32, true);
    assertTrue((icon.getRGB(4, 16) >>> 24) < 20, "transparent column");
    assertEquals("blue", Fixtures.colorName(icon.getRGB(20, 16)));
  }

  private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
    assertEquals(expected.getWidth(), actual.getWidth());
    for (int y = 0; y < expected.getHeight(); y++) {
      for (int x = 0; x < expected.getWidth(); x++) {
        int e = expected.getRGB(x, y), a = actual.getRGB(x, y);
        for (int shift = 0; shift < 32; shift += 8) {
          int de = (e >>> shift) & 255, da = (a >>> shift) & 255;
          assertTrue(Math.abs(de - da) <= 1, "pixel " + x + "," + y + ": " + Integer.toHexString(e) + " vs " + Integer.toHexString(a));
        }
      }
    }
  }
}
