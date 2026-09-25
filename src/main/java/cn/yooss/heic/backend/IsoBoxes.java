package cn.yooss.heic.backend;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Pure-Java structural check of an ISO-BMFF (HEIF) file.
 * <p>
 * System decoders may happily "decode" a truncated HEIC (a partially downloaded or partially written file):
 * macOS ImageIO.framework returns an all-black image without reporting an error. Two cheap checks detect that case
 * before any backend sees the data (see {@link HeifInput}):
 * <ol>
 *   <li>the top-level boxes must fit into the file (a box that claims more bytes than there are means the file was
 *   cut inside that box);</li>
 *   <li>every item extent in the {@code iloc} box that refers to this file must lie inside the file (catches a file
 *   cut exactly at a box boundary, e.g. right before {@code mdat}).</li>
 * </ol>
 * Anything this class does not understand is not judged ({@code null}), so an unusual but valid file is never
 * rejected here; the system decoder decides.
 */
final class IsoBoxes {
  private IsoBoxes() {
  }

  /**
   * Returns a description of the problem if {@code data} is an ISO-BMFF file (starts with an {@code ftyp} box)
   * that is cut short, or {@code null} if it looks complete or is not ISO-BMFF at all.
   */
  static String findTruncation(byte[] data) {
    long length = data.length;
    if (length < 8 || !"ftyp".equals(type(data, 4))) return null;

    long metaStart = -1, metaEnd = -1;
    boolean movie = false;
    long offset = 0;
    while (length - offset >= 8) {
      int at = (int) offset;
      String type = type(data, at + 4);
      if (type == null) break; // trailing bytes that are not a box: leave them to the decoder
      long size = u32(data, at);
      long header = 8;
      if (size == 1) {
        if (length - offset < 16) return describe(type, offset, 16, length - offset);
        size = u64(data, at + 8);
        header = 16;
      }
      else if (size == 0) {
        size = length - offset; // the last box extends to the end of the file
      }
      if (size < 0 || size > length - offset) {
        if (type.equals("free") || type.equals("skip")) break; // padding only, no image data lost
        return describe(type, offset, size, length - offset);
      }
      if (size < header) return null; // malformed; let the decoder decide
      if (type.equals("moov")) movie = true;
      if (type.equals("meta") && metaStart < 0) {
        metaStart = offset + header + 4; // meta is a FullBox: version/flags precede the children
        metaEnd = offset + size;
      }
      offset += size;
    }
    if (metaStart < 0) {
      return movie ? null : "no 'meta' box: the file ends after " + offset + " bytes of headers";
    }
    return checkItemLocations(data, metaStart, metaEnd);
  }

  /** Checks that the file-relative extents of the {@code iloc} box inside {@code meta} lie within the file. */
  private static String checkItemLocations(byte[] data, long metaStart, long metaEnd) {
    long[] iloc = findChild(data, metaStart, metaEnd, "iloc");
    if (iloc == null) return null;
    Reader r = new Reader(data, iloc[0], iloc[1]);
    int version = r.u8();
    r.skip(3); // flags
    int sizes1 = r.u8();
    int sizes2 = r.u8();
    int offsetSize = sizes1 >>> 4, lengthSize = sizes1 & 0xF, baseOffsetSize = sizes2 >>> 4;
    int indexSize = version == 1 || version == 2 ? sizes2 & 0xF : 0;
    if (version > 2 || !validFieldSize(offsetSize) || !validFieldSize(lengthSize) || !validFieldSize(baseOffsetSize)
        || !validFieldSize(indexSize)) {
      return null;
    }
    long itemCount = version < 2 ? r.u16() : r.u32();
    for (long item = 0; item < itemCount && r.ok(); item++) {
      long itemId = version < 2 ? r.u16() : r.u32();
      int constructionMethod = version == 1 || version == 2 ? r.u16() & 0xF : 0;
      int dataReferenceIndex = r.u16();
      long baseOffset = r.uN(baseOffsetSize);
      int extentCount = r.u16();
      // With index/offset/length sizes all 0 every extent reads no bytes and is identical (baseOffset..EOF), so
      // checking one is enough; without this a crafted iloc loops up to 65535 times per 6-byte item.
      if (indexSize + offsetSize + lengthSize == 0) extentCount = Math.min(extentCount, 1);
      for (int extent = 0; extent < extentCount && r.ok(); extent++) {
        r.uN(indexSize);
        long extentOffset = r.uN(offsetSize);
        long extentLength = r.uN(lengthSize);
        if (!r.ok()) return null;
        if (constructionMethod != 0 || dataReferenceIndex != 0) continue; // idat / item / external data
        long start = baseOffset + extentOffset;
        long end = extentLength == 0 ? start : start + extentLength; // length 0 = up to the end of the file
        if (start < 0 || end < start) return null; // overflow: not something we understand
        if (end > data.length || (extentLength == 0 && start >= data.length)) {
          return String.format(Locale.ROOT, "item %d needs data up to byte %d but the file has only %d bytes",
                               itemId, end, data.length);
        }
      }
    }
    return null;
  }

  private static boolean validFieldSize(int size) {
    return size == 0 || size == 4 || size == 8;
  }

  /** Returns {@code {payloadStart, end}} of the first child box of {@code type} in {@code [start, end)}. */
  private static long[] findChild(byte[] data, long start, long end, String wanted) {
    long offset = start;
    while (end - offset >= 8) {
      int at = (int) offset;
      long size = u32(data, at);
      String type = type(data, at + 4);
      if (type == null || size < 8 || size > end - offset) return null;
      if (type.equals(wanted)) return new long[]{offset + 8, offset + size};
      offset += size;
    }
    return null;
  }

  private static String describe(String type, long offset, long needed, long available) {
    String neededText = needed < 0 ? Long.toUnsignedString(needed) : Long.toString(needed);
    return String.format(Locale.ROOT, "box '%s' at offset %d needs %s bytes but only %d are present", type, offset,
                         neededText, available);
  }

  /** The four-character box type, or {@code null} if it is not printable ASCII (i.e. not a box). */
  private static String type(byte[] b, int off) {
    for (int i = off; i < off + 4; i++) {
      if (b[i] < 0x20 || b[i] > 0x7E) return null;
    }
    return new String(b, off, 4, StandardCharsets.ISO_8859_1);
  }

  private static long u32(byte[] b, int off) {
    return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
  }

  private static long u64(byte[] b, int off) {
    return (u32(b, off) << 32) | u32(b, off + 4);
  }

  /** Bounds-checked big-endian reader over {@code [position, end)}; reading past the end sets {@link #ok()} to false. */
  private static final class Reader {
    private final byte[] data;
    private final long end;
    private long position;
    private boolean ok = true;

    Reader(byte[] data, long start, long end) {
      this.data = data;
      this.position = start;
      this.end = end;
    }

    boolean ok() {
      return ok;
    }

    void skip(int n) {
      uN(n);
    }

    int u8() {
      return (int) uN(1);
    }

    int u16() {
      return (int) uN(2);
    }

    long u32() {
      return uN(4);
    }

    long uN(int bytes) {
      if (!ok || end - position < bytes) {
        ok = false;
        return 0;
      }
      long value = 0;
      for (int i = 0; i < bytes; i++) {
        value = (value << 8) | (data[(int) position++] & 0xFF);
      }
      return value;
    }
  }
}
