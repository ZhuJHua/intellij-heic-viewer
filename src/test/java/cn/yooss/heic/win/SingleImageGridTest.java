package cn.yooss.heic.win;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.backend.HeifImageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link SingleImageGrid} on every OS: which files are rewritten, the structure of the result, robustness against
 * damaged files, and (where the system decoder is not the affected Windows one: macOS ImageIO, libheif) that the
 * rewritten file decodes to exactly the same image, orientation, alpha, thumbnail and sequence frame included.
 * {@code WicColorTest} checks the colors on Windows.
 */
class SingleImageGridTest {
  /** Files whose primary image is a single 8-bit HEVC image: rewritten. */
  static final String[] SINGLE = {
    "fixtures/rgb_sips.heic", "fixtures/rgb_libheif.heic", "fixtures/alpha_sips.heic", "fixtures/alpha_libheif.heic",
    "fixtures/exif3_apple.heic", "fixtures/exif5_apple.heic", "fixtures/exif6_apple.heic", "fixtures/rot90_irot.heic",
    "fixtures/fliph_imir.heic", "fixtures/multi.heic", "fixtures/seq.heics", "fixtures/thumb_irot.heic",
    "fixtures/icc_wide.heic", "cn/yooss/heic/win/rgb_bt709.heic", "cn/yooss/heic/win/rgb_limited.heic",
    "cn/yooss/heic/win/rgb_444.heic", "cn/yooss/heic/win/smooth_sips.heic"};

  static byte[] resource(String path) {
    try (InputStream in = SingleImageGridTest.class.getResourceAsStream("/" + path)) {
      if (in == null) throw new IllegalArgumentException("Missing resource " + path);
      return in.readAllBytes();
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "fixtures/rgb_sips.heic", "fixtures/rgb_libheif.heic", "fixtures/alpha_sips.heic", "fixtures/alpha_libheif.heic",
    "fixtures/exif3_apple.heic", "fixtures/exif5_apple.heic", "fixtures/exif6_apple.heic", "fixtures/rot90_irot.heic",
    "fixtures/fliph_imir.heic", "fixtures/multi.heic", "fixtures/seq.heics", "fixtures/thumb_irot.heic",
    "fixtures/icc_wide.heic", "cn/yooss/heic/win/rgb_bt709.heic", "cn/yooss/heic/win/rgb_limited.heic",
    "cn/yooss/heic/win/rgb_444.heic", "cn/yooss/heic/win/smooth_sips.heic"})
  void singleEightBitImagesBecomeAOneByOneGrid(String path) {
    byte[] data = resource(path);
    byte[] grid = SingleImageGrid.wrap(data);
    assertNotNull(grid, path);

    Iso original = new Iso(data), result = new Iso(grid);
    // Nothing moves: the original bytes stay where they are, only the old meta box is renamed to free.
    int oldMeta = original.top("meta").start;
    assertEquals("free", result.typeAt(oldMeta));
    byte[] expectedPrefix = data.clone();
    System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, expectedPrefix, oldMeta + 4, 4);
    assertArrayEquals(expectedPrefix, Arrays.copyOf(grid, data.length), "everything before the appended boxes is unchanged");
    assertEquals(List.of("meta", "mdat"), result.topTypesAfter(data.length));

    // The primary item keeps its ID and is a grid now, with the coded image as its hidden tile.
    long primary = original.primary();
    assertEquals(primary, result.primary());
    assertEquals("hvc1", original.itemType(primary));
    assertEquals("grid", result.itemType(primary));
    long tile = result.maxItemId();
    assertEquals(original.maxItemId() + 1, tile);
    assertEquals("hvc1", result.itemType(tile));
    assertTrue(result.hidden(tile));
    assertTrue(result.references("dimg").contains(primary + ">" + tile));
    // Existing references keep their IDs: those to the primary image (alpha, thumbnail, EXIF) now go to the grid.
    for (String reference : original.references(null)) assertTrue(result.references(null).contains(reference), reference);

    // Properties: the tile has the coded image's, the grid has ispe, colr/pixi and the transformations.
    List<String> before = original.propertyTypes(primary), tileProps = result.propertyTypes(tile);
    List<String> gridProps = result.propertyTypes(primary);
    assertEquals(before.stream().filter(t -> !List.of("irot", "imir", "clap").contains(t)).toList(), tileProps);
    assertEquals("ispe", gridProps.get(0));
    for (String t : before) {
      if (List.of("irot", "imir", "clap", "colr", "pixi").contains(t)) assertTrue(gridProps.contains(t), t + " on the grid");
    }
    assertArrayEquals(original.ispe(primary), result.ispe(primary));

    // The grid payload: version 0, flags 0, 1x1, output size = the image size.
    long[] size = original.ispe(primary);
    byte[] payload = result.itemData(primary);
    assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) (size[0] >> 8), (byte) size[0], (byte) (size[1] >> 8), (byte) size[1]},
                      payload);
    // The coded image's data is where it was.
    assertArrayEquals(original.itemData(primary), result.itemData(tile));

    assertNull(SingleImageGrid.wrap(grid), "a grid is not wrapped again");
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "fixtures/grid_libheif.heic", "fixtures/bands_2000x1200.heic", "fixtures/bands_exif6.heic",
    "cn/yooss/heic/win/p3_grid_sips.heic", "cn/yooss/heic/win/smooth_grid_tc1.heic", // grids
    "fixtures/ten_bit.heic", "fixtures/rgb16_sips.heic", // 10-bit
    "fixtures/rgb.avif", "fixtures/alpha.avif", "fixtures/rgb_sips.avif", // AV1
    "fixtures/garbage.heic", "fixtures/header_only.heic", "fixtures/rgb.png"})
  void otherFilesAreLeftAlone(String path) {
    assertNull(SingleImageGrid.wrap(resource(path)), path);
  }

  @Test
  void monochromeAndHighBitDepthAreLeftAlone() {
    byte[] data = resource("fixtures/rgb_libheif.heic");
    Iso iso = new Iso(data);
    int hvcC = iso.property(iso.primary(), "hvcC").payload;
    byte[] mono = data.clone();
    mono[hvcC + 16] &= (byte) 0xFC; // chromaFormat 0
    assertNull(SingleImageGrid.wrap(mono));
    byte[] tenBit = data.clone();
    tenBit[hvcC + 17] |= 2; // bitDepthLumaMinus8 2
    assertNull(SingleImageGrid.wrap(tenBit));
    assertNull(SingleImageGrid.wrap(new byte[0]));
  }

  /**
   * macOS writes general_progressive_source_flag = 1 into the decoder configuration record, and the decoder then takes
   * its single-image path for a tile that fills the grid: the tile gets a copy of the record with the flag cleared.
   */
  @Test
  void progressiveSourceFlagIsCleared() {
    byte[] data = resource("fixtures/rgb_sips.heic");
    Iso original = new Iso(data);
    Iso.Box config = original.property(original.primary(), "hvcC");
    byte[] payload = Arrays.copyOfRange(data, config.payload, config.end);
    assertTrue((payload[6] & 0x80) != 0, "macOS sets the flag");
    byte[] cleared = SingleImageGrid.withoutProgressiveSource(payload);
    assertNotNull(cleared);
    byte[] expected = payload.clone();
    expected[6] &= 0x7F;
    assertArrayEquals(expected, cleared, "only the flag changes");
    assertNull(SingleImageGrid.withoutProgressiveSource(cleared), "nothing left to clear");

    // the rewritten file: the tile has the new record (a new property), the grid none
    byte[] grid = SingleImageGrid.wrap(data);
    Iso result = new Iso(grid);
    Iso.Box tileConfig = result.property(result.maxItemId(), "hvcC");
    assertArrayEquals(cleared, Arrays.copyOfRange(grid, tileConfig.payload, tileConfig.end));
    assertEquals(original.properties().size() + 2, result.properties().size(), "ispe and hvcC added");

    // libheif leaves the flag clear in the record: the tile keeps its configuration
    byte[] libheif = resource("fixtures/rgb_libheif.heic");
    Iso lh = new Iso(libheif);
    Iso.Box lhConfig = lh.property(lh.primary(), "hvcC");
    assertNull(SingleImageGrid.withoutProgressiveSource(Arrays.copyOfRange(libheif, lhConfig.payload, lhConfig.end)));
    Iso lhResult = new Iso(SingleImageGrid.wrap(libheif));
    assertEquals(lh.properties().size() + 1, lhResult.properties().size(), "ispe added");
  }

  /** The last box may extend to the end of the file (size 0); it gets its real size, since boxes are appended. */
  @Test
  void lastBoxUpToTheEndOfTheFile() {
    byte[] data = resource("fixtures/rgb_libheif.heic");
    Iso iso = new Iso(data);
    Iso.Box mdat = iso.boxes.get(iso.boxes.size() - 1);
    assertEquals("mdat", mdat.type);
    byte[] open = data.clone();
    Arrays.fill(open, mdat.start, mdat.start + 4, (byte) 0);
    byte[] grid = SingleImageGrid.wrap(open);
    assertNotNull(grid);
    Iso result = new Iso(grid);
    assertEquals(mdat.end - mdat.start, result.top("mdat").end - result.top("mdat").start);
    assertArrayEquals(iso.itemData(iso.primary()), result.itemData(result.maxItemId()));
  }

  /** Damaged files never make it throw: it returns a rewrite or null (the decoder then gets the file as it is). */
  @Test
  void damagedFilesDoNotThrow() {
    Random random = new Random(42);
    for (String path : SINGLE) {
      byte[] data = resource(path);
      for (int length = 0; length < data.length; length += Math.max(1, data.length / 97)) {
        SingleImageGrid.wrap(Arrays.copyOf(data, length));
      }
      int metaEnd = new Iso(data).top("meta").end;
      for (int i = 0; i < 300; i++) {
        byte[] damaged = data.clone();
        for (int k = 0; k < 1 + random.nextInt(4); k++) damaged[random.nextInt(metaEnd)] = (byte) random.nextInt(256);
        SingleImageGrid.wrap(damaged);
      }
    }
  }

  /**
   * Where the system decoder is not the one with the bug (macOS ImageIO, libheif), the rewritten file must decode to
   * the same image: size, orientation, alpha and every pixel.
   */
  @ParameterizedTest
  @ValueSource(strings = {
    "fixtures/rgb_sips.heic", "fixtures/rgb_libheif.heic", "fixtures/alpha_sips.heic", "fixtures/alpha_libheif.heic",
    "fixtures/exif3_apple.heic", "fixtures/exif5_apple.heic", "fixtures/exif6_apple.heic", "fixtures/rot90_irot.heic",
    "fixtures/fliph_imir.heic", "fixtures/multi.heic", "fixtures/seq.heics", "fixtures/thumb_irot.heic",
    "fixtures/icc_wide.heic", "cn/yooss/heic/win/rgb_bt709.heic", "cn/yooss/heic/win/rgb_limited.heic",
    "cn/yooss/heic/win/rgb_444.heic", "cn/yooss/heic/win/smooth_sips.heic"})
  void decodesLikeTheOriginalElsewhere(String path) throws IOException {
    HeifBackend backend = HeifBackends.current();
    assumeTrue(backend.status().isAvailable(), "no system decoder");
    assumeFalse(backend.id().equals("windows-wic"), "WicColorTest compares the colors on Windows");
    byte[] data = resource(path);
    byte[] grid = SingleImageGrid.wrap(data);
    assertNotNull(grid);
    HeifImageInfo infoA = backend.readInfo(data), infoB = backend.readInfo(grid);
    assertEquals(infoA.width(), infoB.width());
    assertEquals(infoA.height(), infoB.height());
    assertEquals(infoA.hasAlpha(), infoB.hasAlpha());
    BufferedImage a = backend.decode(data, 0), b = backend.decode(grid, 0);
    assertEquals(a.getWidth() + "x" + a.getHeight(), b.getWidth() + "x" + b.getHeight());
    assertEquals(a.getType(), b.getType());
    assertEquals(Fixtures.layout(a), Fixtures.layout(b));
    // Bit-identical without transformations. With irot/imir, macOS ImageIO's thumbnail-with-transform path (the macOS
    // backend's) resamples a rotated grid slightly differently at the color edges (mean 0.2 to 0.6); libheif's
    // heif-dec and sips give bit-identical images for these files too.
    double mean = Fixtures.meanDifference(a, b);
    assertTrue(mean < 1.0, path + ": mean difference " + mean);
  }

  /** A minimal ISO-BMFF reader for the checks above (the test's own, independent of the code under test). */
  static final class Iso {
    static final class Box {
      final String type;
      final int start, payload, end;

      Box(String type, int start, int payload, int end) {
        this.type = type;
        this.start = start;
        this.payload = payload;
        this.end = end;
      }
    }

    final byte[] data;
    final List<Box> boxes;
    final Box meta;

    Iso(byte[] data) {
      this.data = data;
      this.boxes = boxes(0, data.length);
      this.meta = top("meta");
    }

    Box top(String type) {
      return boxes.stream().filter(b -> b.type.equals(type)).findFirst().orElseThrow();
    }

    String typeAt(int at) {
      return new String(data, at + 4, 4, StandardCharsets.ISO_8859_1);
    }

    List<String> topTypesAfter(int offset) {
      return boxes.stream().filter(b -> b.start >= offset).map(b -> b.type).toList();
    }

    List<Box> boxes(int start, int end) {
      List<Box> result = new ArrayList<>();
      int at = start;
      while (at + 8 <= end) {
        long size = u32(at);
        int header = 8;
        if (size == 1) {
          size = u32(at + 8) << 32 | u32(at + 12);
          header = 16;
        }
        else if (size == 0) {
          size = end - at;
        }
        result.add(new Box(new String(data, at + 4, 4, StandardCharsets.ISO_8859_1), at, at + header, (int) (at + size)));
        at += (int) size;
      }
      return result;
    }

    Box child(Box parent, String type, int skip) {
      return boxes(parent.payload + skip, parent.end).stream().filter(b -> b.type.equals(type)).findFirst().orElse(null);
    }

    long primary() {
      Box pitm = child(meta, "pitm", 4);
      return data[pitm.payload] == 0 ? u16(pitm.payload + 4) : u32(pitm.payload + 4);
    }

    List<Box> infes() {
      Box iinf = child(meta, "iinf", 4);
      return boxes(iinf.payload + (data[iinf.payload] == 0 ? 6 : 8), iinf.end);
    }

    long infeId(Box infe) {
      return data[infe.payload] == 2 ? u16(infe.payload + 4) : u32(infe.payload + 4);
    }

    Box infe(long id) {
      return infes().stream().filter(b -> infeId(b) == id).findFirst().orElseThrow();
    }

    String itemType(long id) {
      Box infe = infe(id);
      int at = infe.payload + (data[infe.payload] == 2 ? 8 : 10);
      return new String(data, at, 4, StandardCharsets.ISO_8859_1);
    }

    boolean hidden(long id) {
      return (data[infe(id).payload + 3] & 1) != 0;
    }

    long maxItemId() {
      return infes().stream().mapToLong(this::infeId).max().orElseThrow();
    }

    /** "from>to" for every reference of {@code type} (all types if null), as "type:from>to" then. */
    List<String> references(String type) {
      List<String> result = new ArrayList<>();
      Box iref = child(meta, "iref", 4);
      if (iref == null) return result;
      int idSize = data[iref.payload] == 0 ? 2 : 4;
      for (Box r : boxes(iref.payload + 4, iref.end)) {
        if (type != null && !r.type.equals(type)) continue;
        long from = idSize == 2 ? u16(r.payload) : u32(r.payload);
        int n = u16(r.payload + idSize);
        for (int k = 0; k < n; k++) {
          int at = r.payload + idSize + 2 + k * idSize;
          long to = idSize == 2 ? u16(at) : u32(at);
          result.add((type == null ? r.type + ":" : "") + from + ">" + to);
        }
      }
      return result;
    }

    List<Box> properties() {
      Box iprp = child(meta, "iprp", 4);
      Box ipco = child(iprp, "ipco", 0);
      return boxes(ipco.payload, ipco.end);
    }

    List<Integer> propertyIndices(long id) {
      Box iprp = child(meta, "iprp", 4);
      Box ipma = child(iprp, "ipma", 0);
      int version = data[ipma.payload], flags = (int) (u32(ipma.payload) & 0xFFFFFF);
      int at = ipma.payload + 8;
      for (long i = 0, n = u32(ipma.payload + 4); i < n; i++) {
        long item = version < 1 ? u16(at) : u32(at);
        at += version < 1 ? 2 : 4;
        int count = data[at++] & 0xFF;
        List<Integer> indices = new ArrayList<>();
        for (int k = 0; k < count; k++) {
          if ((flags & 1) != 0) {
            indices.add(u16(at) & 0x7FFF);
            at += 2;
          }
          else {
            indices.add(data[at++] & 0x7F);
          }
        }
        if (item == id) return indices;
      }
      throw new AssertionError("no ipma entry for " + id);
    }

    List<String> propertyTypes(long id) {
      List<Box> properties = properties();
      return propertyIndices(id).stream().map(i -> properties.get(i - 1).type).toList();
    }

    Box property(long id, String type) {
      List<Box> properties = properties();
      return propertyIndices(id).stream().map(i -> properties.get(i - 1)).filter(b -> b.type.equals(type))
        .findFirst().orElseThrow();
    }

    long[] ispe(long id) {
      Box ispe = property(id, "ispe");
      return new long[]{u32(ispe.payload + 4), u32(ispe.payload + 8)};
    }

    /** The item's data (construction method 0 only, which is all the fixtures use). */
    byte[] itemData(long id) {
      Box iloc = child(meta, "iloc", 4);
      int version = data[iloc.payload];
      int sizes = data[iloc.payload + 4] & 0xFF, sizes2 = data[iloc.payload + 5] & 0xFF;
      int offsetSize = sizes >>> 4, lengthSize = sizes & 15, baseSize = sizes2 >>> 4;
      int indexSize = version >= 1 ? sizes2 & 15 : 0;
      int at = iloc.payload + 6;
      long count = version < 2 ? u16(at) : u32(at);
      at += version < 2 ? 2 : 4;
      for (long i = 0; i < count; i++) {
        long item = version < 2 ? u16(at) : u32(at);
        at += version < 2 ? 2 : 4;
        if (version >= 1) {
          assertEquals(0, u16(at) & 15, "construction method");
          at += 2;
        }
        at += 2;
        long base = n(at, baseSize);
        at += baseSize;
        int extents = u16(at);
        at += 2;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int e = 0; e < extents; e++) {
          at += indexSize;
          long offset = n(at, offsetSize);
          at += offsetSize;
          long length = n(at, lengthSize);
          at += lengthSize;
          long start = base + offset;
          out.write(data, (int) start, (int) (length == 0 ? data.length - start : length));
        }
        if (item == id) return out.toByteArray();
      }
      throw new AssertionError("no iloc entry for " + id);
    }

    long n(int at, int size) {
      return size == 0 ? 0 : size == 4 ? u32(at) : u32(at) << 32 | u32(at + 4);
    }

    int u16(int at) {
      return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
    }

    long u32(int at) {
      return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | data[at + 3] & 0xFFL;
    }
  }
}
