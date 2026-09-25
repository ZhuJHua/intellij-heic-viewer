package cn.yooss.heic.win;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NclxTransfer}: which transfer characteristics are presented as sRGB, and that nothing else changes. */
class NclxTransferTest {
  /**
   * macOS writes nclx (2, 2, 6, 1), heif-enc can write BT.709 (1): the transfer (bytes 6-7 of the nclx payload) becomes
   * 13, nothing else changes.
   */
  @ParameterizedTest
  @ValueSource(strings = {"fixtures/rgb_sips.heic", "fixtures/rgb16_sips.heic", "fixtures/alpha_sips.heic",
    "fixtures/bands_2000x1200.heic", "fixtures/seq.heics", "cn/yooss/heic/win/smooth_sips.heic",
    "cn/yooss/heic/win/smooth_grid_tc1.heic"})
  void sdrCurvesBecomeSrgb(String path) {
    byte[] data = SingleImageGridTest.resource(path);
    byte[] result = NclxTransfer.asSrgb(data);
    assertNotNull(result, path);
    assertEquals(data.length, result.length);
    int changed = 0;
    for (int i = 0; i < data.length; i++) {
      if (data[i] != result[i]) {
        changed++;
        assertTrue(NclxTransfer.convertedByWindows(data[i]), "was " + data[i]);
        assertEquals(13, result[i]);
        assertEquals("nclx", new String(data, i - 7, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
      }
    }
    assertTrue(changed >= 1, path);
    assertNull(NclxTransfer.asSrgb(result), "nothing left to change");
  }

  /** sRGB already (libheif), ICC profiles only (iPhone-style), and not HEIF at all: nothing to do. */
  @ParameterizedTest
  @ValueSource(strings = {"fixtures/rgb_libheif.heic", "fixtures/grid_libheif.heic", "fixtures/ten_bit.heic",
    "cn/yooss/heic/win/p3_grid_sips.heic", "fixtures/icc_wide.heic", "fixtures/garbage.heic", "fixtures/header_only.heic",
    "fixtures/rgb.png"})
  void othersAreLeftAlone(String path) {
    assertNull(NclxTransfer.asSrgb(SingleImageGridTest.resource(path)), path);
  }

  @Test
  void onlyTheSdrCurvesWindowsConverts() {
    for (int transfer = 0; transfer < 256; transfer++) {
      boolean converted = transfer == 1 || transfer == 2 || transfer == 6 || transfer == 14 || transfer == 15;
      assertEquals(converted, NclxTransfer.convertedByWindows(transfer), "transfer " + transfer);
    }
    assertFalse(NclxTransfer.convertedByWindows(16), "PQ is left to the decoder");
    assertFalse(NclxTransfer.convertedByWindows(18), "HLG is left to the decoder");
    assertNull(NclxTransfer.asSrgb(new byte[0]));
  }
}
