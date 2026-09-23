package cn.yooss.heic.thumbnail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailGeometryTest {
  @ParameterizedTest(name = "{0}x{1} in {2}")
  @CsvSource({
      // width, height, box -> x, y, w, h
      "600, 400, 32,   0,  5, 32, 21", // landscape: full width, centered vertically
      "400, 600, 32,   5,  0, 21, 32", // portrait (e.g. EXIF orientation 6 applied)
      "500, 500, 32,   0,  0, 32, 32",
      "600, 400, 16,   0,  2, 16, 11", // odd padding (5 rows): the extra row goes to the bottom
      "4032, 3024, 32, 0,  4, 32, 24", // iPhone 4:3
      "1000, 1, 32,    0, 15, 32,  1", // extreme panorama keeps at least one row
      "1, 1000, 16,    7,  0,  1, 16",
      "4, 2, 32,       0,  8, 32, 16", // small images are scaled up
      "1, 1, 16,       0,  0, 16, 16",
      "600, 400, 1,    0,  0,  1,  1",
  })
  void fitKeepsAspectRatioAndCenters(int width, int height, int box, int x, int y, int w, int h) {
    Rectangle r = ThumbnailGeometry.fit(width, height, box);
    assertEquals(new Rectangle(x, y, w, h), r);
    assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.width <= box && r.y + r.height <= box, "inside the box: " + r);
  }

  @Test
  void fitPreservesAspectRatioWithinRounding() {
    for (int w = 1; w <= 300; w += 7) {
      for (int h = 1; h <= 300; h += 11) {
        for (int box : new int[]{16, 20, 24, 32, 64}) {
          Rectangle r = ThumbnailGeometry.fit(w, h, box);
          assertEquals(box, Math.max(r.width, r.height), "longer side fills the box");
          double expected = (double) Math.min(w, h) / Math.max(w, h) * box;
          assertTrue(Math.abs(Math.min(r.width, r.height) - Math.max(1, expected)) <= 0.5 + 1e-9,
                     w + "x" + h + " in " + box + " -> " + r);
          assertTrue(Math.abs((box - r.width) - 2 * r.x) <= 1 && Math.abs((box - r.height) - 2 * r.y) <= 1, "centered");
        }
      }
    }
  }

  @Test
  void fitRejectsInvalidInput() {
    assertThrows(IllegalArgumentException.class, () -> ThumbnailGeometry.fit(0, 10, 16));
    assertThrows(IllegalArgumentException.class, () -> ThumbnailGeometry.fit(10, -1, 16));
    assertThrows(IllegalArgumentException.class, () -> ThumbnailGeometry.fit(10, 10, 0));
  }

  @ParameterizedTest
  @CsvSource({"16, 1.0, 16", "16, 2.0, 32", "16, 1.5, 24", "16, 1.25, 20", "20, 2.0, 40", "16, 0.5, 16", "16, 9.0, 64",
      "16, NaN, 16"})
  void variantSize(int iconSize, double scale, int expected) {
    assertEquals(expected, ThumbnailGeometry.variantSize(iconSize, scale));
  }

  @ParameterizedTest
  @CsvSource({"16, 32", "32, 64", "40, 80", "8, 32", "200, 256"})
  void decodeSizeIsTwiceTheLargestVariantWithinBounds(int largestVariant, int expected) {
    assertEquals(expected, ThumbnailGeometry.decodeSize(largestVariant));
  }

  @Test
  void scalesAndPercent() {
    assertEquals(100, ThumbnailGeometry.scalePercent(1.0));
    assertEquals(200, ThumbnailGeometry.scalePercent(2.0));
    assertEquals(150, ThumbnailGeometry.scalePercent(1.5));
    assertEquals(100, ThumbnailGeometry.scalePercent(0.75));
    assertEquals(400, ThumbnailGeometry.scalePercent(8.0));
    assertEquals(100, ThumbnailGeometry.scalePercent(Double.POSITIVE_INFINITY));
    assertArrayEquals(new double[]{1.0}, ThumbnailGeometry.variantScales(100));
    assertArrayEquals(new double[]{1.0, 2.0}, ThumbnailGeometry.variantScales(200));
    assertArrayEquals(new double[]{1.0, 1.5}, ThumbnailGeometry.variantScales(150));
  }

  @Test
  void maxScreenScaleIsOneWhenHeadless() {
    // The tests run with -Djava.awt.headless=true.
    assertEquals(1.0, ThumbnailGeometry.maxScreenScale());
  }

  @Test
  void keyIdentifiesFileVersionAndSize() {
    ThumbnailKey key = ThumbnailKey.of("file:///a.heic", 1000L, 5000L, 16, 2.0);
    assertEquals(new ThumbnailKey("file:///a.heic", 1000L, 5000L, 16, 200), key);
    assertEquals(key.hashCode(), ThumbnailKey.of("file:///a.heic", 1000L, 5000L, 16, 2.0).hashCode());
    assertNotEquals(key, ThumbnailKey.of("file:///a.heic", 1001L, 5000L, 16, 2.0), "modified");
    assertNotEquals(key, ThumbnailKey.of("file:///a.heic", 1000L, 5001L, 16, 2.0), "other length");
    assertNotEquals(key, ThumbnailKey.of("file:///a.heic", 1000L, 5000L, 20, 2.0), "other UI scale");
    assertNotEquals(key, ThumbnailKey.of("file:///a.heic", 1000L, 5000L, 16, 1.0), "other screen scale");
    assertNotEquals(key, ThumbnailKey.of("file:///b.heic", 1000L, 5000L, 16, 2.0), "other file");

    assertEquals(64, key.decodeSize());
    assertArrayEquals(new double[]{1.0, 2.0}, key.scales());
    assertEquals(32, ThumbnailKey.of("u", 0, 1, 16, 1.0).decodeSize());
    assertEquals(60, ThumbnailKey.of("u", 0, 1, 20, 1.5).decodeSize());
    assertThrows(IllegalArgumentException.class, () -> new ThumbnailKey("u", 0, 1, 0, 100));
    assertThrows(IllegalArgumentException.class, () -> new ThumbnailKey("u", 0, 1, 16, 99));
    assertThrows(NullPointerException.class, () -> new ThumbnailKey(null, 0, 1, 16, 100));
  }
}
