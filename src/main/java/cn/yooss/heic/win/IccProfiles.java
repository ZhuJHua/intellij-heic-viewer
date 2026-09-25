package cn.yooss.heic.win;

import java.nio.charset.StandardCharsets;

/**
 * Pure-Java peek into ICC profiles: whether a profile embedded in an image is (practically) sRGB, so that the
 * comparatively slow conversion to sRGB can be skipped. Decided by colorimetry, not by name: the red, green and blue
 * colorants ({@code rXYZ}, {@code gXYZ}, {@code bXYZ}, D50-adapted) must be those of sRGB, and the tone curves must
 * not be linear (a "linear sRGB" profile has sRGB's colorants but needs converting).
 */
final class IccProfiles {
  /** sRGB colorants adapted to D50 (the ICC profile connection space), as in the sRGB IEC61966-2.1 profiles. */
  private static final double[][] SRGB_COLORANTS = {{0.4361, 0.2225, 0.0139}, {0.3851, 0.7169, 0.0971}, {0.1431, 0.0606, 0.7141}};
  private static final double TOLERANCE = 0.003;

  private IccProfiles() {
  }

  /** Whether {@code profile} is an RGB display profile with sRGB colorants and non-linear tone curves. */
  static boolean isSrgb(byte[] profile) {
    if (profile == null || profile.length < 132) return false;
    if (!"acsp".equals(ascii(profile, 36, 4)) || !"RGB ".equals(ascii(profile, 16, 4))) return false;
    String[] colorants = {"rXYZ", "gXYZ", "bXYZ"};
    for (int i = 0; i < 3; i++) {
      double[] xyz = xyz(profile, colorants[i]);
      if (xyz == null) return false;
      for (int c = 0; c < 3; c++) {
        if (Math.abs(xyz[c] - SRGB_COLORANTS[i][c]) > TOLERANCE) return false;
      }
    }
    for (String curve : new String[]{"rTRC", "gTRC", "bTRC"}) {
      if (!isNonLinearCurve(profile, curve)) return false;
    }
    return true;
  }

  /** The profile description ({@code desc} tag, v2 {@code desc} or v4 {@code mluc} type), or {@code null}. */
  static String description(byte[] profile) {
    int[] tag = tag(profile, "desc");
    if (tag == null) return null;
    int start = tag[0], end = tag[0] + tag[1];
    String type = ascii(profile, start, 4);
    if ("desc".equals(type)) {
      long length = u32(profile, start + 8);
      if (length <= 0 || start + 12 + length > end) return null;
      return trimNul(new String(profile, start + 12, (int) length, StandardCharsets.ISO_8859_1));
    }
    if ("mluc".equals(type)) {
      long records = u32(profile, start + 8);
      if (records < 1 || tag[1] < 28) return null;
      long length = u32(profile, start + 20), textOffset = u32(profile, start + 24);
      if (textOffset < 0 || length < 0 || textOffset + length > tag[1]) return null;
      return trimNul(new String(profile, (int) (start + textOffset), (int) length, StandardCharsets.UTF_16BE));
    }
    return null;
  }

  /** {@code {offset, size}} of the tag {@code signature}, or {@code null}. */
  private static int[] tag(byte[] profile, String signature) {
    if (profile == null || profile.length < 132) return null;
    long count = u32(profile, 128);
    if (count < 0 || count > (profile.length - 132) / 12) return null;
    for (int i = 0; i < count; i++) {
      int entry = 132 + 12 * i;
      if (!signature.equals(ascii(profile, entry, 4))) continue;
      long offset = u32(profile, entry + 4), size = u32(profile, entry + 8);
      if (offset < 0 || size < 8 || offset + size > profile.length) return null;
      return new int[]{(int) offset, (int) size};
    }
    return null;
  }

  private static double[] xyz(byte[] profile, String signature) {
    int[] tag = tag(profile, signature);
    if (tag == null || tag[1] < 20 || !"XYZ ".equals(ascii(profile, tag[0], 4))) return null;
    return new double[]{s15Fixed16(profile, tag[0] + 8), s15Fixed16(profile, tag[0] + 12), s15Fixed16(profile, tag[0] + 16)};
  }

  /** A {@code curv} with a table or a gamma other than 1, or a {@code para} whose gamma is not 1. */
  private static boolean isNonLinearCurve(byte[] profile, String signature) {
    int[] tag = tag(profile, signature);
    if (tag == null || tag[1] < 12) return false;
    String type = ascii(profile, tag[0], 4);
    if ("curv".equals(type)) {
      long count = u32(profile, tag[0] + 8);
      if (count == 0) return false; // identity
      if (count == 1) return tag[1] >= 14 && Math.abs(u16(profile, tag[0] + 12) / 256.0 - 1.0) > 0.01;
      return true;
    }
    if ("para".equals(type)) {
      return tag[1] >= 16 && Math.abs(s15Fixed16(profile, tag[0] + 12) - 1.0) > 0.01;
    }
    return false;
  }

  private static String trimNul(String text) {
    int end = text.indexOf('\0');
    return end >= 0 ? text.substring(0, end) : text;
  }

  private static String ascii(byte[] b, int offset, int length) {
    if (offset < 0 || offset + length > b.length) return null;
    return new String(b, offset, length, StandardCharsets.ISO_8859_1);
  }

  private static long u32(byte[] b, int offset) {
    if (offset < 0 || offset + 4 > b.length) return -1;
    return (b[offset] & 0xFFL) << 24 | (b[offset + 1] & 0xFFL) << 16 | (b[offset + 2] & 0xFFL) << 8 | b[offset + 3] & 0xFFL;
  }

  private static int u16(byte[] b, int offset) {
    return (b[offset] & 0xFF) << 8 | b[offset + 1] & 0xFF;
  }

  private static double s15Fixed16(byte[] b, int offset) {
    return (int) u32(b, offset) / 65536.0;
  }
}
