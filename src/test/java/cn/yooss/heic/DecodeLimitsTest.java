package cn.yooss.heic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Java, runs on every OS. */
class DecodeLimitsTest {

  @Test
  void defaults() {
    assertEquals(64_000_000L, DecodeLimits.DEFAULT.maxPixels());
    assertEquals(16384, DecodeLimits.DEFAULT.maxSide());
  }

  @Test
  void imagesWithinTheBudgetAreDecodedAtFullSize() {
    assertEquals(0, DecodeLimits.DEFAULT.maxPixelSizeFor(4032, 3024));  // 12 MP iPhone photo
    assertEquals(0, DecodeLimits.DEFAULT.maxPixelSizeFor(8064, 6048));  // 48 MP
    assertEquals(0, DecodeLimits.DEFAULT.maxPixelSizeFor(16384, 100));  // longest side exactly at the limit
    assertEquals(0, DecodeLimits.DEFAULT.maxPixelSizeFor(8000, 8000));  // exactly 64 MP
  }

  @Test
  void largeImagesAreDownscaledToTheBudget() {
    int side = DecodeLimits.DEFAULT.maxPixelSizeFor(7080, 17784); // 126 MP contact sheet
    assertTrue(side > 0 && side < 17784);
    double scale = side / 17784.0;
    assertTrue(7080 * scale * side <= 64_000_000L, "pixels after downscaling must fit the budget");
    assertTrue(7080 * scale * side > 63_000_000L, "but not be downscaled much more than necessary");
  }

  @Test
  void longestSideIsLimited() {
    assertEquals(16384, DecodeLimits.DEFAULT.maxPixelSizeFor(20000, 1000)); // 20 MP panorama
    assertEquals(300, new DecodeLimits(Long.MAX_VALUE, 300).maxPixelSizeFor(600, 400));
  }

  @Test
  void smallBudget() {
    DecodeLimits limits = new DecodeLimits(10_000, 16384);
    int side = limits.maxPixelSizeFor(600, 400);
    assertEquals(122, side); // floor(600 * sqrt(10000 / 240000))
  }

  @Test
  void megapixelsAreClamped() {
    assertEquals(1_000_000L, DecodeLimits.ofMegapixels(0).maxPixels());
    assertEquals(1_000_000L, DecodeLimits.ofMegapixels(-5).maxPixels());
    assertEquals(512_000_000L, DecodeLimits.ofMegapixels(100_000).maxPixels());
    assertEquals(DecodeLimits.DEFAULT, DecodeLimits.ofMegapixels(64));
  }

  /** Hand-written (not a record, see the class comment): value semantics as a record would have them. */
  @Test
  void valueSemantics() {
    assertEquals(new DecodeLimits(10_000, 300), new DecodeLimits(10_000, 300));
    assertEquals(new DecodeLimits(10_000, 300).hashCode(), new DecodeLimits(10_000, 300).hashCode());
    assertNotEquals(new DecodeLimits(10_000, 300), new DecodeLimits(10_001, 300));
    assertNotEquals(new DecodeLimits(10_000, 300), new DecodeLimits(10_000, 301));
    assertNotEquals(new DecodeLimits(10_000, 300), "DecodeLimits[maxPixels=10000, maxSide=300]");
    assertEquals("DecodeLimits[maxPixels=10000, maxSide=300]", new DecodeLimits(10_000, 300).toString());
  }

  @Test
  void invalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> new DecodeLimits(0, 100));
    assertThrows(IllegalArgumentException.class, () -> new DecodeLimits(100, 0));
    assertEquals(0, DecodeLimits.DEFAULT.maxPixelSizeFor(0, 100));
  }
}
