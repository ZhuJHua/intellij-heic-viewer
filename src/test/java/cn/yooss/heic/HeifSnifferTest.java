package cn.yooss.heic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Java, runs on every OS. */
class HeifSnifferTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "rgb_sips.heic", "rgb_libheif.heic", "alpha_sips.heic", "alpha_libheif.heic", "rgb16_sips.heic", "ten_bit.heic",
      "exif3_apple.heic", "exif5_apple.heic", "exif6_apple.heic", "rot90_irot.heic", "fliph_imir.heic",
      "grid_libheif.heic", "multi.heic", "seq.heics",
      "header_only.heic" /* valid ftyp, no image: accepted by the sniffer, rejected by the decoder */})
  void acceptsHeifFixtures(String name) {
    assertTrue(sniff(Fixtures.bytes(name)), name);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb.avif", "alpha.avif", "rgb_sips.avif", "rgb.png", "alpha.png", "rgb16.png", "garbage.heic"})
  void rejectsOtherFixtures(String name) {
    assertFalse(sniff(Fixtures.bytes(name)), name);
  }

  @Test
  void rejectsMp4AndQuickTime() {
    assertFalse(sniff(ftyp("isom", "isom", "iso2", "avc1", "mp41")), "MP4 isom");
    assertFalse(sniff(ftyp("mp42", "mp42", "isom")), "MP4 mp42");
    assertFalse(sniff(ftyp("qt  ", "qt  ")), "QuickTime MOV");
    assertFalse(sniff(ftyp("M4A ", "M4A ", "mp42", "isom")), "M4A");
    assertFalse(sniff(ftyp("3gp4", "isom", "3gp4")), "3GP");
    assertFalse(sniff(ftyp("crx ", "crx ", "isom")), "Canon CR3");
  }

  @Test
  void brandRules() {
    assertTrue(sniff(ftyp("heic", "mif1", "heic")));
    assertTrue(sniff(ftyp("mif1", "mif1", "heic")), "HEVC brand among the compatible brands");
    assertTrue(sniff(ftyp("heix")));
    assertTrue(sniff(ftyp("hevc", "msf1", "hevc")), "HEVC image sequence");
    assertTrue(sniff(ftyp("mif1", "mif1")), "generic HEIF without AVIF brand");
    assertTrue(sniff(ftyp("msf1", "msf1", "iso8")), "generic HEIF sequence");
    assertTrue(sniff(ftyp("mif2", "mif2")));
    assertTrue(sniff(ftyp("miaf", "miaf")));
    assertTrue(sniff(ftyp("heic", "avif", "mif1")), "an HEVC brand wins even if AVIF is listed too");

    assertFalse(sniff(ftyp("avif", "mif1", "miaf")), "AVIF");
    assertFalse(sniff(ftyp("mif1", "mif1", "avif")), "AVIF declared through mif1 + avif");
    assertFalse(sniff(ftyp("avis", "msf1", "avis")), "AVIF sequence");
    assertFalse(sniff(ftyp("mif1", "mif1", "miaf", "avio")), "AVIF intra-only");
    assertFalse(sniff(ftyp("jpeg", "jpeg")), "unknown brands only");
  }

  @Test
  void readsBrandsBeyondTheFirst64Bytes() {
    String[] brands = new String[20];
    Arrays.fill(brands, "iso8");
    brands[brands.length - 1] = "heic"; // at offset 16 + 19 * 4 = 92
    assertTrue(sniff(ftyp("mif1", brands)));

    brands[brands.length - 1] = "avif";
    assertFalse(sniff(ftyp("mif1", brands)), "an AVIF brand late in the list must still be seen");
  }

  @Test
  void ignoresBrandsBeyondTheCap() {
    String[] brands = new String[200]; // ftyp of 816 bytes
    Arrays.fill(brands, "iso8");
    brands[brands.length - 1] = "heic";
    byte[] data = ftyp("iso8", brands);
    assertEquals(HeifSniffer.MAX_HEADER_BYTES, HeifSniffer.headerBytesNeeded(data, 8));
    assertFalse(sniff(data));
  }

  @Test
  void onlyReadsUpToTheFtypBox() {
    byte[] data = ftyp("mif1", "mif1", "miaf"); // 24 bytes
    byte[] followedByAvifLookalike = concat(data, "\0\0\0\u000Cfreeavif".getBytes(StandardCharsets.ISO_8859_1));
    assertEquals(24, HeifSniffer.headerBytesNeeded(followedByAvifLookalike, 8));
    assertTrue(sniff(followedByAvifLookalike), "bytes after the ftyp box are not brands");
  }

  @Test
  void rejectsMalformedHeaders() {
    assertFalse(sniff(new byte[0]));
    assertFalse(HeifSniffer.isHeif(null, 0));
    assertFalse(sniff("\0\0\0\u0018ftyp".getBytes(StandardCharsets.ISO_8859_1)), "too short");
    byte[] tooSmall = ftyp("heic", "mif1");
    tooSmall[3] = 12; // box size smaller than type + major + minor
    assertFalse(sniff(tooSmall));
    byte[] notFtyp = ftyp("heic", "mif1");
    notFtyp[4] = 'm';
    notFtyp[5] = 'o';
    notFtyp[6] = 'o';
    notFtyp[7] = 'v';
    assertFalse(sniff(notFtyp));
    assertEquals(0, HeifSniffer.headerBytesNeeded(notFtyp, 8));
  }

  @Test
  void handlesSizeZeroAndLargeSize() {
    byte[] toEof = ftyp("heic", "mif1");
    toEof[0] = toEof[1] = toEof[2] = toEof[3] = 0; // size 0: box extends to the end of the file
    assertTrue(sniff(toEof));

    ByteArrayOutputStream large = new ByteArrayOutputStream();
    large.writeBytes(new byte[]{0, 0, 0, 1});
    large.writeBytes("ftyp".getBytes(StandardCharsets.ISO_8859_1));
    large.writeBytes(new byte[]{0, 0, 0, 0, 0, 0, 0, 32}); // largesize = 32
    large.writeBytes("heic\0\0\0\0mif1".getBytes(StandardCharsets.ISO_8859_1));
    assertTrue(sniff(large.toByteArray()));
  }

  private static boolean sniff(byte[] data) {
    int needed = HeifSniffer.headerBytesNeeded(data, Math.min(8, data.length));
    int n = needed == 0 ? Math.min(data.length, 8) : Math.min(needed, data.length);
    return HeifSniffer.isHeif(Arrays.copyOf(data, Math.max(n, 0)), n);
  }

  /** Builds an ftyp box: size, "ftyp", major brand, minor version 0, compatible brands. */
  static byte[] ftyp(String major, String... compatible) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int size = 16 + 4 * compatible.length;
    out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
    out.writeBytes("ftyp".getBytes(StandardCharsets.ISO_8859_1));
    out.writeBytes(major.getBytes(StandardCharsets.ISO_8859_1));
    out.writeBytes(new byte[4]);
    for (String brand : compatible) out.writeBytes(brand.getBytes(StandardCharsets.ISO_8859_1));
    return out.toByteArray();
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] result = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }
}
