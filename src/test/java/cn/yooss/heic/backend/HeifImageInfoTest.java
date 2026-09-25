package cn.yooss.heic.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Pure Java, runs on every OS. */
class HeifImageInfoTest {
  @ParameterizedTest
  @CsvSource({"1, 600x400", "2, 600x400", "3, 600x400", "4, 600x400", "5, 400x600", "6, 400x600", "7, 400x600",
              "8, 400x600", "0, 600x400", "9, 600x400", "-1, 600x400"})
  void displaySize(int orientation, String expected) {
    HeifImageInfo info = new HeifImageInfo("heic", 1, 0, 600, 400, orientation, 8, false);
    assertEquals(expected, info.width() + "x" + info.height());
    assertEquals(orientation >= 1 && orientation <= 8 ? orientation : 1, info.orientation(), "invalid values count as 1");
  }

  @Test
  void valueSemantics() {
    HeifImageInfo info = new HeifImageInfo("public.heic", 2, 1, 600, 400, 6, 10, true);
    assertEquals(new HeifImageInfo("public.heic", 2, 1, 600, 400, 6, 10, true), info);
    assertEquals(new HeifImageInfo("public.heic", 2, 1, 600, 400, 6, 10, true).hashCode(), info.hashCode());
    assertNotEquals(new HeifImageInfo("public.heic", 2, 1, 600, 400, 6, 10, false), info);
    assertNotEquals(new HeifImageInfo(null, 2, 1, 600, 400, 6, 10, true), info);
    assertEquals("HeifImageInfo[type=public.heic, images=2, primary=1, raw=600x400, orientation=6, bitDepth=10, alpha=true]",
                 info.toString());
  }
}
