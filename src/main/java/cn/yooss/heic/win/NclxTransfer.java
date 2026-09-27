package cn.yooss.heic.win;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Presents the SDR transfer curves that Microsoft's HEIF decoder converts to sRGB as sRGB, so that Windows shows them
 * like macOS ImageIO and libheif do.
 * <p>
 * For 10-bit images and grids ({@link SingleImageGrid}), the HEIF Image Extension converts the H.273
 * {@code transfer_characteristics} 1, 6, 14 and 15 (BT.709 and its equivalents) and 2 (unspecified) to sRGB, which
 * brightens shadows and midtones; with 13 (sRGB) it converts nothing. The rewrite sets those two bytes of every
 * {@code nclx} box in the item properties to 13, in a copy of the file; other values (PQ, HLG, linear, ...) are left
 * alone.
 */
final class NclxTransfer {
  /** H.273 transfer characteristics 13: IEC 61966-2-1 (sRGB). */
  static final int SRGB = 13;

  private NclxTransfer() {
  }

  /** Whether the decoder converts this transfer curve to sRGB, which the other platforms do not do. */
  static boolean convertedByWindows(int transfer) {
    return transfer == 1 || transfer == 2 || transfer == 6 || transfer == 14 || transfer == 15;
  }

  /** A copy with those transfer characteristics set to sRGB, or {@code null} if there are none (or no HEIF meta box). */
  static byte @Nullable [] asSrgb(byte[] data) {
    return patch(data, false);
  }

  /**
   * Sets those transfer characteristics to sRGB in {@code data} itself (a copy the caller owns); returns whether there
   * were any.
   */
  static boolean asSrgbInPlace(byte[] data) {
    return patch(data, true) != null;
  }

  private static byte @Nullable [] patch(byte[] data, boolean inPlace) {
    try {
      int[] meta = child(data, 0, data.length, "meta", true);
      if (meta == null) return null;
      int[] iprp = child(data, meta[0] + 4, meta[1], "iprp", false);
      if (iprp == null) return null;
      int[] ipco = child(data, iprp[0], iprp[1], "ipco", false);
      if (ipco == null) return null;
      byte[] result = null;
      int at = ipco[0];
      while (at + 8 <= ipco[1]) {
        long size = u32(data, at);
        if (size < 8 || size > ipco[1] - at) return null;
        int payload = at + 8, end = (int) (at + size);
        if (type(data, at + 4).equals("colr") && end - payload >= 11 && type(data, payload).equals("nclx")) {
          int transfer = u16(data, payload + 6);
          if (convertedByWindows(transfer)) {
            if (result == null) result = inPlace ? data : data.clone();
            result[payload + 6] = 0;
            result[payload + 7] = SRGB;
          }
        }
        at = end;
      }
      return result;
    }
    catch (RuntimeException e) {
      return null;
    }
  }

  /** {@code {payloadStart, end}} of the first box of {@code type} in {@code [start, end)}, or {@code null}. */
  private static int @Nullable [] child(byte[] data, int start, int end, String type, boolean topLevel) {
    long at = start;
    while (end - at >= 8) {
      int p = (int) at;
      long size = u32(data, p);
      int header = 8;
      if (size == 1 && end - at >= 16) {
        size = u32(data, p + 8) << 32 | u32(data, p + 12);
        header = 16;
      }
      else if (size == 0 && topLevel) {
        size = end - at;
      }
      if (size < header || size > end - at) return null;
      if (type(data, p + 4).equals(type)) return new int[]{p + header, (int) (at + size)};
      at += size;
    }
    return null;
  }

  private static String type(byte[] data, int at) {
    return new String(data, at, 4, StandardCharsets.ISO_8859_1);
  }

  private static int u16(byte[] data, int at) {
    return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
  }

  private static long u32(byte[] data, int at) {
    return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | data[at + 3] & 0xFFL;
  }
}
