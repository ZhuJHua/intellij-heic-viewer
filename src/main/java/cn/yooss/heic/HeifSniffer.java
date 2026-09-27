package cn.yooss.heic;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Pure-Java content sniffer for HEIC/HEIF files (ISO-BMFF {@code ftyp} box).
 * <p>
 * This class is used from {@link HeicImageReaderSpi#canDecodeInput(Object)}, which ImageIO calls for
 * <em>every</em> image the IDE loads. It therefore must stay cheap, must never throw and must never touch
 * native code.
 * <p>
 * Accepted: an HEVC brand ({@code heic heix hevc hevx heim heis hevm hevs}) anywhere in the {@code ftyp}
 * box, or a generic HEIF brand ({@code mif1 msf1 mif2 miaf}) when no AVIF brand ({@code avif avis avio}) is
 * present. AVIF, MP4/MOV ({@code isom}, {@code mp42}, {@code qt  } ...) and everything that does not start with an
 * {@code ftyp} box are rejected.
 */
public final class HeifSniffer {
  /** Maximum number of header bytes inspected; {@code ftyp} boxes are far smaller in practice. */
  public static final int MAX_HEADER_BYTES = 512;

  private static final Set<String> HEVC_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs");
  private static final Set<String> GENERIC_HEIF_BRANDS = Set.of("mif1", "msf1", "mif2", "miaf");
  private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis", "avio");

  private HeifSniffer() {
  }

  /**
   * Returns how many header bytes {@link #isHeif(byte[], int)} needs, given the first 8 bytes of a file:
   * the size of the {@code ftyp} box capped to {@link #MAX_HEADER_BYTES}, or {@code 0} if the data does not
   * start with an {@code ftyp} box at all (so the caller can stop reading early).
   */
  public static int headerBytesNeeded(byte[] first8, int length) {
    if (first8 == null || length < 8 || !isFtyp(first8)) return 0;
    long size = u32(first8, 0);
    if (size == 0 || size == 1) return MAX_HEADER_BYTES; // extends to EOF / 64-bit size: read the cap
    if (size < 16) return 0;
    return (int) Math.min(size, MAX_HEADER_BYTES);
  }

  /** Returns {@code true} if {@code header[0..length)} starts with an {@code ftyp} box of a HEIC/HEIF file. */
  public static boolean isHeif(byte[] header, int length) {
    if (header == null) return false;
    int n = Math.min(Math.min(length, header.length), MAX_HEADER_BYTES);
    if (n < 16 || !isFtyp(header)) return false;

    long size = u32(header, 0);
    int brandsStart = 16; // size(4) type(4) major_brand(4) minor_version(4) compatible_brands(4*k)
    int majorOffset = 8;
    if (size == 1) { // 64-bit "largesize" follows the type
      if (n < 24) return false;
      long large = u64(header, 8);
      if (large < 24) return false;
      size = large;
      majorOffset = 16;
      brandsStart = 24;
    } else if (size == 0) {
      size = n; // box extends to the end of the file
    } else if (size < 16) {
      return false;
    }
    int end = (int) Math.min(size, n);
    if (majorOffset + 4 > end) return false;

    Brands brands = new Brands();
    brands.add(header, majorOffset);
    for (int off = brandsStart; off + 4 <= end; off += 4) {
      brands.add(header, off);
    }
    return brands.hevc || (brands.generic && !brands.avif);
  }

  private static final class Brands {
    boolean hevc, generic, avif;

    void add(byte[] b, int off) {
      String brand = new String(b, off, 4, StandardCharsets.ISO_8859_1);
      hevc |= HEVC_BRANDS.contains(brand);
      generic |= GENERIC_HEIF_BRANDS.contains(brand);
      avif |= AVIF_BRANDS.contains(brand);
    }
  }

  private static boolean isFtyp(byte[] b) {
    return b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p';
  }

  static long u32(byte[] b, int off) {
    return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
  }

  static long u64(byte[] b, int off) {
    return (u32(b, off) << 32) | u32(b, off + 4);
  }
}
