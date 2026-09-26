package cn.yooss.heic.backend;

import cn.yooss.heic.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Java, runs on every OS. */
class IsoBoxesTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "rgb_sips.heic", "rgb_libheif.heic", "alpha_sips.heic", "alpha_libheif.heic", "rgb16_sips.heic", "ten_bit.heic",
      "exif3_apple.heic", "exif5_apple.heic", "exif6_apple.heic", "rot90_irot.heic", "fliph_imir.heic",
      "grid_libheif.heic", "multi.heic", "seq.heics", "bands_2000x1200.heic", "bands_exif6.heic", "rgb.avif"})
  void completeFilesPass(String name) {
    assertNull(IsoBoxes.findTruncation(Fixtures.bytes(name)));
  }

  @Test
  void nonIsoBmffDataIsNotJudged() {
    assertNull(IsoBoxes.findTruncation(Fixtures.bytes("rgb.png")));
    assertNull(IsoBoxes.findTruncation(Fixtures.bytes("garbage.heic")));
    assertNull(IsoBoxes.findTruncation(new byte[3]));
  }

  @Test
  void truncatedFilesAreDetected() {
    byte[] full = Fixtures.bytes("grid_libheif.heic");
    for (int length = 9; length < full.length; length += 97) {
      assertNotNull(IsoBoxes.findTruncation(Arrays.copyOf(full, length)), "cut at " + length);
    }
    assertNotNull(IsoBoxes.findTruncation(Fixtures.bytes("header_only.heic")));
    // cut exactly after the ftyp box, and exactly at / a few bytes after the end of the meta box (no mdat left)
    for (int length : new int[]{28, 31, 1430, 1431, 1437}) {
      assertNotNull(IsoBoxes.findTruncation(Arrays.copyOf(full, length)), "cut at " + length);
    }
  }

  @Test
  void itemDataInIdatIsNotCheckedAgainstTheFile() {
    ByteArrayOutputStream meta = new ByteArrayOutputStream();
    meta.writeBytes(new byte[4]); // version + flags
    meta.writeBytes(box("hdlr", new byte[25]));
    meta.writeBytes(box("iloc", ilocV1(1 /* idat */, 5000, 100)));
    meta.writeBytes(box("idat", new byte[10]));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(box("ftyp", "heic\0\0\0\0mif1".getBytes(StandardCharsets.ISO_8859_1)));
    out.writeBytes(box("meta", meta.toByteArray()));
    assertNull(IsoBoxes.findTruncation(out.toByteArray()));
  }

  @Test
  void itemExtentsMustLieInsideTheFile() {
    // ftyp (20) + meta (8 + 4 + hdlr 33 + iloc) + mdat (8 + 20); the item points at the mdat payload.
    byte[] ftyp = box("ftyp", "heic\0\0\0\0mif1".getBytes(StandardCharsets.ISO_8859_1));
    int metaSize = 8 + 4 + 33 + (8 + ilocV0(0, 0).length);
    int payloadOffset = ftyp.length + metaSize + 8;
    byte[] complete = heifWithItemAt(payloadOffset, 20, 20);
    assertNull(IsoBoxes.findTruncation(complete));

    byte[] withoutMdat = Arrays.copyOf(complete, ftyp.length + metaSize); // cut exactly at a box boundary
    assertNotNull(IsoBoxes.findTruncation(withoutMdat));

    byte[] pointsBeyond = heifWithItemAt(payloadOffset, 21, 20); // extent one byte longer than the data
    assertNotNull(IsoBoxes.findTruncation(pointsBeyond));
  }

  /** All iloc field sizes 0 (65535 items with 65535 extents each, 393 KB): each extent is checked once, within a second. */
  @Test
  @Timeout(1)
  void zeroWidthExtentsAreCheckedOnce() {
    ByteArrayOutputStream iloc = new ByteArrayOutputStream();
    iloc.writeBytes(new byte[]{0, 0, 0, 0});       // version 0, flags
    iloc.writeBytes(new byte[]{0x00, 0x00});       // offset_size 0, length_size 0, base_offset_size 0, reserved
    iloc.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF}); // item_count 65535
    for (int item = 1; item <= 0xFFFF; item++) {
      iloc.writeBytes(new byte[]{(byte) (item >>> 8), (byte) item}); // item_ID
      iloc.writeBytes(new byte[]{0, 0});                              // data_reference_index
      iloc.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF});          // extent_count 65535
    }
    byte[] data = heifWithIloc(iloc.toByteArray());
    assertTrue(data.length > 390_000, "size " + data.length);
    assertNull(IsoBoxes.findTruncation(data)); // every extent is baseOffset 0 .. end of file
  }

  @Test
  void zeroWidthExtentsBeyondTheFileAreStillReported() {
    ByteArrayOutputStream iloc = new ByteArrayOutputStream();
    iloc.writeBytes(new byte[]{0, 0, 0, 0});       // version 0, flags
    iloc.writeBytes(new byte[]{0x00, 0x40});       // offset_size 0, length_size 0, base_offset_size 4, reserved
    iloc.writeBytes(new byte[]{0, 1});             // item_count
    iloc.writeBytes(new byte[]{0, 1});             // item_ID
    iloc.writeBytes(new byte[]{0, 0});             // data_reference_index
    iloc.writeBytes(int32(1_000_000));             // base_offset, past the end of the file
    iloc.writeBytes(new byte[]{0, 5});             // extent_count
    String truncation = IsoBoxes.findTruncation(heifWithIloc(iloc.toByteArray()));
    assertNotNull(truncation);
    assertTrue(truncation.contains("item 1"), truncation);
  }

  /** ftyp + meta{hdlr, iloc} + a small mdat. */
  private static byte[] heifWithIloc(byte[] ilocPayload) {
    ByteArrayOutputStream meta = new ByteArrayOutputStream();
    meta.writeBytes(new byte[4]);
    meta.writeBytes(box("hdlr", new byte[25]));
    meta.writeBytes(box("iloc", ilocPayload));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(box("ftyp", "heic\0\0\0\0mif1".getBytes(StandardCharsets.ISO_8859_1)));
    out.writeBytes(box("meta", meta.toByteArray()));
    out.writeBytes(box("mdat", new byte[20]));
    return out.toByteArray();
  }

  private static byte[] heifWithItemAt(int offset, int length, int mdatPayload) {
    ByteArrayOutputStream meta = new ByteArrayOutputStream();
    meta.writeBytes(new byte[4]);
    meta.writeBytes(box("hdlr", new byte[25]));
    meta.writeBytes(box("iloc", ilocV0(offset, length)));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(box("ftyp", "heic\0\0\0\0mif1".getBytes(StandardCharsets.ISO_8859_1)));
    out.writeBytes(box("meta", meta.toByteArray()));
    out.writeBytes(box("mdat", new byte[mdatPayload]));
    return out.toByteArray();
  }

  /** iloc version 0, offset/length size 4, base offset size 0, one item with one extent. */
  private static byte[] ilocV0(int offset, int length) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{0, 0, 0, 0});       // version 0, flags
    out.writeBytes(new byte[]{0x44, 0x00});       // offset_size 4, length_size 4, base_offset_size 0, reserved
    out.writeBytes(new byte[]{0, 1});             // item_count
    out.writeBytes(new byte[]{0, 1});             // item_ID
    out.writeBytes(new byte[]{0, 0});             // data_reference_index
    out.writeBytes(new byte[]{0, 1});             // extent_count
    out.writeBytes(int32(offset));
    out.writeBytes(int32(length));
    return out.toByteArray();
  }

  /** iloc version 1 with the given construction method. */
  private static byte[] ilocV1(int constructionMethod, int offset, int length) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{1, 0, 0, 0});       // version 1, flags
    out.writeBytes(new byte[]{0x44, 0x00});       // offset_size 4, length_size 4, base_offset_size 0, index_size 0
    out.writeBytes(new byte[]{0, 1});             // item_count
    out.writeBytes(new byte[]{0, 1});             // item_ID
    out.writeBytes(new byte[]{0, (byte) constructionMethod});
    out.writeBytes(new byte[]{0, 0});             // data_reference_index
    out.writeBytes(new byte[]{0, 1});             // extent_count
    out.writeBytes(int32(offset));
    out.writeBytes(int32(length));
    return out.toByteArray();
  }

  private static byte[] int32(int v) {
    return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
  }

  @Test
  void trailingBytesThatAreNotBoxesAreIgnored() {
    byte[] full = Fixtures.bytes("rgb_sips.heic");
    byte[] withJunk = Arrays.copyOf(full, full.length + 12);
    Arrays.fill(withJunk, full.length, withJunk.length, (byte) 0xFF);
    assertNull(IsoBoxes.findTruncation(withJunk));
    assertNull(IsoBoxes.findTruncation(Arrays.copyOf(full, full.length + 5)), "fewer than 8 trailing bytes");
  }

  @Test
  void sizeZeroAndLargeSize() {
    ByteArrayOutputStream out = header();
    out.writeBytes(new byte[]{0, 0, 0, 0});
    out.writeBytes("mdat".getBytes(StandardCharsets.ISO_8859_1));
    out.writeBytes(new byte[100]);
    assertNull(IsoBoxes.findTruncation(out.toByteArray()), "size 0 = extends to end of file");

    ByteArrayOutputStream large = header();
    large.writeBytes(new byte[]{0, 0, 0, 1});
    large.writeBytes("mdat".getBytes(StandardCharsets.ISO_8859_1));
    large.writeBytes(new byte[]{0, 0, 0, 0, 0, 0, 0, 116}); // 16-byte header + 100 bytes
    large.writeBytes(new byte[100]);
    assertNull(IsoBoxes.findTruncation(large.toByteArray()));
    byte[] cut = Arrays.copyOf(large.toByteArray(), large.size() - 1);
    assertNotNull(IsoBoxes.findTruncation(cut));
  }

  @Test
  void truncatedFreeBoxIsOnlyPadding() {
    ByteArrayOutputStream out = header();
    out.writeBytes(box("mdat", new byte[20]));
    out.writeBytes(new byte[]{0, 0, 0, 64});
    out.writeBytes("free".getBytes(StandardCharsets.ISO_8859_1));
    assertNull(IsoBoxes.findTruncation(out.toByteArray()));
  }

  @Test
  void headersWithoutMetaAreReported() {
    assertNotNull(IsoBoxes.findTruncation(box("ftyp", "heic\0\0\0\0mif1".getBytes(StandardCharsets.ISO_8859_1))), "ftyp only");
  }

  /** ftyp + a meta box with an hdlr child. */
  private static ByteArrayOutputStream header() {
    ByteArrayOutputStream meta = new ByteArrayOutputStream();
    meta.writeBytes(new byte[4]); // version + flags
    meta.writeBytes(box("hdlr", new byte[25]));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(box("ftyp", "heic\0\0\0\0mif1".getBytes(StandardCharsets.ISO_8859_1)));
    out.writeBytes(box("meta", meta.toByteArray()));
    return out;
  }

  private static byte[] box(String type, byte[] payload) {
    int size = 8 + payload.length;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[]{(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
    out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
    out.writeBytes(payload);
    return out.toByteArray();
  }
}
