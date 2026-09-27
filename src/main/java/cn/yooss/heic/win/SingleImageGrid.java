package cn.yooss.heic.win;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Gives Microsoft's HEIF decoder a file whose primary image is a single 8-bit HEVC image as a 1x1 grid of that image,
 * which it decodes with the right colors: for a single 8-bit {@code hvc1} image the decoder uses the BT.709 matrix
 * whatever the file signals, for a grid it honors the {@code nclx} matrix and range. Pure Java; nothing is re-encoded,
 * and WIC reads a rewritten copy in memory.
 * <p>
 * <b>The rewrite</b>, only when the primary item is an 8-bit {@code hvc1} image with chroma and the file uses nothing
 * this class does not understand (otherwise {@link #wrap} returns {@code null} and the file is decoded as it is):
 * <ul>
 *   <li>a new item of type {@code grid} takes over the primary item's ID, so {@code pitm} and every reference to the
 *   primary image now refer to the grid;</li>
 *   <li>the coded image gets a new ID (one above the largest item or entity group ID), is marked hidden and becomes
 *   the grid's only tile ({@code dimg});</li>
 *   <li>the grid gets an {@code ispe} of the image's size, shares the tile's {@code colr} and {@code pixi} properties
 *   and takes over the transformative properties ({@code irot}, {@code imir}, {@code clap});</li>
 *   <li>if the tile's {@code hvcC} has {@code general_progressive_source_flag} set, the tile gets a copy with the flag
 *   cleared ({@link #withoutProgressiveSource});</li>
 *   <li>the grid's {@code ImageGrid} payload goes into an {@code mdat} box at the end.</li>
 * </ul>
 * Nothing in the file moves: the old {@code meta} box becomes a {@code free} box of the same size and the new one is
 * appended after the last box, so the {@code iloc} offsets stay valid.
 */
final class SingleImageGrid {
  /** Properties that apply to the reconstructed (derived) image, so they move to the grid. */
  private static final Set<String> TRANSFORMATIVE = Set.of("irot", "imir", "clap");
  /** Properties the grid shares with its tile. */
  private static final Set<String> SHARED = Set.of("colr", "pixi");
  /** The essential properties the primary image may have; with any other essential property the file is not changed. */
  private static final Set<String> KNOWN_ESSENTIAL = Set.of("hvcC", "ispe", "colr", "pixi", "irot", "imir", "clap");

  private SingleImageGrid() {
  }

  /** The file as a 1x1 grid, or {@code null} if it does not need the rewrite or is not understood. */
  static byte @Nullable [] wrap(byte[] data) {
    try {
      return new Rewrite(data).run();
    }
    catch (Malformed | RuntimeException e) {
      return null; // not understood: WIC gets the file as it is
    }
  }

  /** Something in the file that this class does not handle; the file is then decoded unchanged. */
  private static final class Malformed extends Exception {
    Malformed(String message) {
      super(message, null, false, false);
    }
  }

  /** A box: its type, and where its header, payload and end are in the file. */
  private static final class Box {
    final String type;
    final int start;
    final int payload;
    final int end;

    Box(String type, int start, int payload, int end) {
      this.type = type;
      this.start = start;
      this.payload = payload;
      this.end = end;
    }
  }

  /** An item property association: the 1-based property index, and whether the property is essential. */
  private static final class Association {
    final int index;
    final boolean essential;

    Association(int index, boolean essential) {
      this.index = index;
      this.essential = essential;
    }
  }

  private static final class Rewrite {
    private final byte[] data;

    Rewrite(byte[] data) {
      this.data = data;
    }

    byte @Nullable [] run() throws Malformed {
      List<Box> top = boxes(0, data.length, true);
      if (top.isEmpty() || !top.get(0).type.equals("ftyp") || count(top, "meta") != 1) return null;
      Box meta = only(top, "meta");
      List<Box> children = boxes(meta.payload + 4, meta.end, false);
      Box pitm = only(children, "pitm"), iinf = only(children, "iinf"), iloc = only(children, "iloc");
      Box iprp = only(children, "iprp"), iref = only(children, "iref"), grpl = only(children, "grpl");
      if (pitm == null || iinf == null || iloc == null || iprp == null) return null;
      if (count(children, "iref") > 1 || count(children, "grpl") > 1) return null;

      // pitm: the primary item, which becomes the grid (it keeps its ID)
      long primary = u8(pitm.payload) == 0 ? u16(pitm.payload + 4) : u32(pitm.payload + 4);

      // iinf: the primary item must be hvc1; the largest item ID
      int iinfVersion = u8(iinf.payload);
      int entriesStart = iinf.payload + (iinfVersion == 0 ? 6 : 8);
      long itemCount = iinfVersion == 0 ? u16(iinf.payload + 4) : u32(iinf.payload + 4);
      if (iinfVersion == 0 && itemCount >= 0xFFFF) return null;
      long maxId = primary;
      Box primaryEntry = null;
      for (Box entry : boxes(entriesStart, iinf.end, false)) {
        if (!entry.type.equals("infe")) continue;
        int version = u8(entry.payload);
        if (version < 2) return null; // no item type
        long id = version == 2 ? u16(entry.payload + 4) : u32(entry.payload + 4);
        maxId = Math.max(maxId, id);
        if (id == primary) {
          if (primaryEntry != null || !"hvc1".equals(fourCc(entry.payload + (version == 2 ? 8 : 10)))) return null;
          primaryEntry = entry;
        }
      }
      if (primaryEntry == null) return null;
      if (grpl != null) { // entity groups share the ID space of the items
        for (Box group : boxes(grpl.payload, grpl.end, false)) maxId = Math.max(maxId, u32(group.payload + 4));
      }
      long tileId = maxId + 1;
      boolean wideIds = tileId > 0xFFFF;
      if (tileId > 0xFFFFFFFFL || wideIds && u8(primaryEntry.payload) == 2) return null;

      // ipco, ipma (one box): the primary image's properties
      List<Box> iprpChildren = boxes(iprp.payload, iprp.end, false);
      Box ipco = only(iprpChildren, "ipco"), ipma = only(iprpChildren, "ipma");
      if (ipco == null || ipma == null) return null;
      List<Box> properties = boxes(ipco.payload, ipco.end, false);
      int ipmaVersion = u8(ipma.payload);
      int ipmaFlags = (int) (u32(ipma.payload) & 0xFFFFFF);
      if (wideIds && ipmaVersion < 1) return null;
      List<Long> ipmaIds = new ArrayList<>();
      List<List<Association>> associations = new ArrayList<>();
      int at = ipma.payload + 8;
      for (long i = 0, n = u32(ipma.payload + 4); i < n; i++) {
        ipmaIds.add(ipmaVersion < 1 ? (long) u16(at) : u32(at));
        at += ipmaVersion < 1 ? 2 : 4;
        int count = u8(at++);
        List<Association> list = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
          if ((ipmaFlags & 1) != 0) {
            int value = u16(at);
            at += 2;
            list.add(new Association(value & 0x7FFF, (value & 0x8000) != 0));
          }
          else {
            int value = u8(at++);
            list.add(new Association(value & 0x7F, (value & 0x80) != 0));
          }
        }
        associations.add(list);
      }
      if (at > ipma.end || ipmaIds.indexOf(primary) < 0 || ipmaIds.indexOf(primary) != ipmaIds.lastIndexOf(primary)) {
        return null;
      }
      int primaryIndex = ipmaIds.indexOf(primary);

      Box hvcC = null, ispe = null;
      List<Association> tile = new ArrayList<>(), grid = new ArrayList<>(), moved = new ArrayList<>();
      for (Association a : associations.get(primaryIndex)) {
        if (a.index < 1 || a.index > properties.size()) return null;
        Box property = properties.get(a.index - 1);
        if (a.essential && !KNOWN_ESSENTIAL.contains(property.type)) return null;
        if (property.type.equals("hvcC")) hvcC = property;
        if (property.type.equals("ispe")) ispe = property;
        if (TRANSFORMATIVE.contains(property.type)) {
          moved.add(a);
        }
        else {
          tile.add(a);
          if (SHARED.contains(property.type)) grid.add(new Association(a.index, false));
        }
      }
      if (hvcC == null || ispe == null || hvcC.end - hvcC.payload < 23 || ispe.end - ispe.payload < 12) return null;
      // HEVCDecoderConfigurationRecord: chromaFormat at 16, bitDepthLumaMinus8 at 17, bitDepthChromaMinus8 at 18
      int chromaFormat = u8(hvcC.payload + 16) & 3;
      int lumaBits = (u8(hvcC.payload + 17) & 7) + 8, chromaBits = (u8(hvcC.payload + 18) & 7) + 8;
      if (lumaBits != 8 || chromaBits != 8 || chromaFormat == 0) return null; // 10-bit is right; monochrome: no matrix
      long width = u32(ispe.payload + 4), height = u32(ispe.payload + 8);
      if (width == 0 || height == 0) return null;

      int ispeIndex = properties.size() + 1;
      // The tile's decoder configuration without general_progressive_source_flag (a copy, as a new property).
      byte[] tileConfig = withoutProgressiveSource(Arrays.copyOfRange(data, hvcC.payload, hvcC.end));
      int configIndex = tileConfig != null ? ispeIndex + 1 : -1;
      if (Math.max(ispeIndex, configIndex) > 0x7FFF) return null;
      if (tileConfig != null) {
        for (int i = 0; i < tile.size(); i++) {
          Association a = tile.get(i);
          if (properties.get(a.index - 1) == hvcC) tile.set(i, new Association(configIndex, a.essential));
        }
      }
      grid.add(0, new Association(ispeIndex, false));
      grid.addAll(moved); // transformative properties last, in their order
      associations.set(primaryIndex, grid);
      ipmaIds.add(tileId); // IDs stay in increasing order: the tile's is the largest
      associations.add(tile);
      boolean wideIndices = (ipmaFlags & 1) != 0 || Math.max(ispeIndex, configIndex) > 0x7F;

      // iref: the grid's tile (all other references keep their IDs, so they refer to the grid now)
      int irefVersion = iref != null ? u8(iref.payload) : wideIds ? 1 : 0;
      if (irefVersion > 1 || wideIds && irefVersion == 0) return null;

      // iloc: the coded image's entry gets the tile's ID; the grid gets an entry for its payload
      int ilocVersion = u8(iloc.payload);
      if (ilocVersion > 2 || wideIds && ilocVersion < 2) return null;
      int sizes = u8(iloc.payload + 4), sizes2 = u8(iloc.payload + 5);
      int offsetSize = sizes >>> 4, lengthSize = sizes & 0xF, baseOffsetSize = sizes2 >>> 4;
      int indexSize = ilocVersion == 1 || ilocVersion == 2 ? sizes2 & 0xF : 0;
      if (!validSize(offsetSize) || !validSize(lengthSize) || !validSize(baseOffsetSize) || !validSize(indexSize)) return null;
      if (offsetSize == 0 && baseOffsetSize == 0) return null; // nowhere to store the grid payload's offset
      int idSize = ilocVersion < 2 ? 2 : 4;
      int ilocItemsAt = iloc.payload + 6 + idSize;
      long ilocCount = ilocVersion < 2 ? u16(iloc.payload + 6) : u32(iloc.payload + 6);
      if (ilocVersion < 2 && ilocCount >= 0xFFFF) return null;
      int primaryIdField = -1;
      at = ilocItemsAt;
      for (long i = 0; i < ilocCount; i++) {
        long id = ilocVersion < 2 ? u16(at) : u32(at);
        if (id == primary) {
          if (primaryIdField >= 0) return null;
          primaryIdField = at;
        }
        at += idSize + (ilocVersion >= 1 ? 2 : 0) + 2 + baseOffsetSize;
        int extents = u16(at);
        at += 2 + extents * (indexSize + offsetSize + lengthSize);
      }
      if (primaryIdField < 0 || at > iloc.end) return null;

      // ImageGrid: version 0, flags (1: 32-bit sizes), rows - 1 = 0, columns - 1 = 0, output width and height
      boolean wideGrid = width > 0xFFFF || height > 0xFFFF;
      int fieldBytes = wideGrid ? 4 : 2;
      byte[] gridPayload = new byte[4 + 2 * fieldBytes];
      gridPayload[1] = (byte) (wideGrid ? 1 : 0);
      putN(gridPayload, 4, width, fieldBytes);
      putN(gridPayload, 4 + fieldBytes, height, fieldBytes);

      // The last top-level box may extend to the end of the file (size 0): it gets its size, since boxes follow now.
      Box last = top.get(top.size() - 1);
      boolean lastToEnd = u32(last.start) == 0;

      // The new meta box; the payload's offset is patched in once its size is known.
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      body.write(data, meta.payload, 4);
      int offsetField = -1, offsetFieldSize = 0;
      boolean irefWritten = false;
      for (Box child : children) {
        if (child == iinf) {
          ByteArrayOutputStream b = new ByteArrayOutputStream();
          b.write(data, iinf.payload, 4);
          writeN(b, itemCount + 1, iinfVersion == 0 ? 2 : 4);
          for (Box entry : boxes(entriesStart, iinf.end, false)) {
            if (entry.start == primaryEntry.start) { // the grid takes the primary item's place and ID
              ByteArrayOutputStream infe = new ByteArrayOutputStream();
              boolean v3 = primary > 0xFFFF;
              infe.write(new byte[]{(byte) (v3 ? 3 : 2), 0, 0, 0}, 0, 4);
              writeN(infe, primary, v3 ? 4 : 2);
              writeN(infe, 0, 2); // item_protection_index
              infe.write("grid".getBytes(StandardCharsets.ISO_8859_1), 0, 4);
              infe.write(0); // item_name ""
              writeBox(b, "infe", infe.toByteArray());
            }
            else {
              b.write(data, entry.start, entry.end - entry.start);
            }
          }
          // the coded image, hidden (flags bit 0), with the tile's ID
          byte[] tileEntry = Arrays.copyOfRange(data, primaryEntry.start, primaryEntry.end);
          int payload = primaryEntry.payload - primaryEntry.start;
          tileEntry[payload + 3] |= 1;
          putN(tileEntry, payload + 4, tileId, u8(primaryEntry.payload) == 2 ? 2 : 4);
          b.write(tileEntry, 0, tileEntry.length);
          writeBox(body, "iinf", b.toByteArray());
          if (iref == null) {
            writeBox(body, "iref", iref(null, irefVersion, primary, tileId));
            irefWritten = true;
          }
        }
        else if (child == iref) {
          writeBox(body, "iref", iref(iref, irefVersion, primary, tileId));
          irefWritten = true;
        }
        else if (child == iprp) {
          ByteArrayOutputStream b = new ByteArrayOutputStream();
          for (Box c : iprpChildren) {
            if (c == ipco) {
              ByteArrayOutputStream props = new ByteArrayOutputStream();
              props.write(data, ipco.payload, ipco.end - ipco.payload);
              ByteArrayOutputStream ispeBody = new ByteArrayOutputStream();
              writeN(ispeBody, 0, 4);
              writeN(ispeBody, width, 4);
              writeN(ispeBody, height, 4);
              writeBox(props, "ispe", ispeBody.toByteArray());
              if (tileConfig != null) writeBox(props, "hvcC", tileConfig);
              writeBox(b, "ipco", props.toByteArray());
            }
            else if (c == ipma) {
              ByteArrayOutputStream m = new ByteArrayOutputStream();
              m.write(ipmaVersion);
              writeN(m, wideIndices ? ipmaFlags | 1 : ipmaFlags, 3);
              writeN(m, ipmaIds.size(), 4);
              for (int i = 0; i < ipmaIds.size(); i++) {
                writeN(m, ipmaIds.get(i), ipmaVersion < 1 ? 2 : 4);
                List<Association> list = associations.get(i);
                if (list.size() > 0xFF) return null;
                m.write(list.size());
                for (Association a : list) {
                  if (wideIndices) writeN(m, (a.essential ? 0x8000 : 0) | a.index, 2);
                  else m.write((a.essential ? 0x80 : 0) | a.index);
                }
              }
              writeBox(b, "ipma", m.toByteArray());
            }
            else {
              b.write(data, c.start, c.end - c.start);
            }
          }
          writeBox(body, "iprp", b.toByteArray());
        }
        else if (child == iloc) {
          ByteArrayOutputStream b = new ByteArrayOutputStream();
          b.write(data, iloc.payload, 6);
          writeN(b, ilocCount + 1, idSize);
          int itemsAt = b.size();
          b.write(data, ilocItemsAt, iloc.end - ilocItemsAt);
          byte[] items = b.toByteArray();
          putN(items, itemsAt + primaryIdField - ilocItemsAt, tileId, idSize); // the coded image is the tile now
          b = new ByteArrayOutputStream();
          b.write(items, 0, items.length);
          // the grid: construction method 0 (this file), one extent, at the end of the file
          writeN(b, primary, idSize);
          if (ilocVersion >= 1) writeN(b, 0, 2);
          writeN(b, 0, 2); // data_reference_index
          int field = b.size();
          writeN(b, 0, baseOffsetSize);
          writeN(b, 1, 2);
          writeN(b, 0, indexSize);
          if (offsetSize != 0) field = b.size();
          writeN(b, 0, offsetSize);
          writeN(b, gridPayload.length, lengthSize); // without a length field: up to the end of the file, where it is
          offsetField = 8 + body.size() + 8 + field; // meta box header, the body so far, the iloc box header
          offsetFieldSize = offsetSize != 0 ? offsetSize : baseOffsetSize;
          writeBox(body, "iloc", b.toByteArray());
        }
        else {
          body.write(data, child.start, child.end - child.start);
        }
      }
      if (!irefWritten || offsetField < 0) return null;

      byte[] metaBody = body.toByteArray();
      long metaSize = 8L + metaBody.length;
      long payloadAt = data.length + metaSize + 8;
      long total = payloadAt + gridPayload.length;
      if (total > Integer.MAX_VALUE - 16 || offsetFieldSize == 4 && payloadAt > 0xFFFFFFFFL) return null;
      if (lastToEnd && last.end - last.start > 0xFFFFFFFFL) return null;

      byte[] result = Arrays.copyOf(data, (int) total);
      putN(result, meta.start + 4, 0x66726565L, 4); // "meta" -> "free"
      if (lastToEnd) putN(result, last.start, last.end - last.start, 4);
      int p = data.length;
      putN(result, p, metaSize, 4);
      putN(result, p + 4, 0x6D657461L, 4); // "meta"
      System.arraycopy(metaBody, 0, result, p + 8, metaBody.length);
      putN(result, p + offsetField, payloadAt, offsetFieldSize);
      p += (int) metaSize;
      putN(result, p, 8 + gridPayload.length, 4);
      putN(result, p + 4, 0x6D646174L, 4); // "mdat"
      System.arraycopy(gridPayload, 0, result, p + 8, gridPayload.length);
      return result;
    }

    /** The {@code iref} payload: the existing references (if any) and {@code dimg} from the grid to its tile. */
    private byte[] iref(@Nullable Box iref, int version, long grid, long tile) {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      if (iref != null) {
        b.write(data, iref.payload, iref.end - iref.payload);
      }
      else {
        b.write(version);
        writeN(b, 0, 3);
      }
      int idSize = version == 0 ? 2 : 4;
      ByteArrayOutputStream dimg = new ByteArrayOutputStream();
      writeN(dimg, grid, idSize);
      writeN(dimg, 1, 2);
      writeN(dimg, tile, idSize);
      writeBox(b, "dimg", dimg.toByteArray());
      return b.toByteArray();
    }

    /** The boxes in {@code [start, end)}; the top level may end with a box of size 0 (up to the end of the file). */
    private List<Box> boxes(int start, int end, boolean topLevel) throws Malformed {
      List<Box> result = new ArrayList<>();
      long at = start;
      while (at < end) {
        if (end - at < 8) throw new Malformed("box header");
        int p = (int) at;
        long size = u32(p);
        int header = 8;
        if (size == 1) {
          if (end - at < 16) throw new Malformed("largesize");
          size = u32(p + 8) << 32 | u32(p + 12);
          header = 16;
        }
        else if (size == 0) {
          if (!topLevel) throw new Malformed("size 0");
          size = end - at;
        }
        if (size < header || size > end - at) throw new Malformed("box size");
        result.add(new Box(fourCc(p + 4), p, p + header, (int) (at + size)));
        at += size;
      }
      return result;
    }

    private String fourCc(int at) {
      check(at, 4);
      return new String(data, at, 4, StandardCharsets.ISO_8859_1);
    }

    private int u8(int at) {
      check(at, 1);
      return data[at] & 0xFF;
    }

    private int u16(int at) {
      check(at, 2);
      return (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
    }

    private long u32(int at) {
      check(at, 4);
      return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8 | data[at + 3] & 0xFFL;
    }

    private void check(int at, int n) {
      if (at < 0 || at > data.length - n) throw new IndexOutOfBoundsException(at);
    }
  }

  /**
   * The {@code hvcC} payload with {@code general_progressive_source_flag} cleared in the record's
   * {@code general_constraint_indicator_flags}, or {@code null} if it is clear already. With the flag set, the decoder
   * takes its single-image path for a tile that fills the whole grid. The flag does not change how a picture is decoded;
   * the parameter sets are left as they are.
   */
  static byte @Nullable [] withoutProgressiveSource(byte[] config) {
    if (config.length < 23 || (config[6] & 0x80) == 0) return null;
    byte[] result = config.clone();
    result[6] &= 0x7F;
    return result;
  }

  private static @Nullable Box only(List<Box> boxes, String type) {
    Box found = null;
    for (Box box : boxes) {
      if (box.type.equals(type)) {
        if (found != null) return null;
        found = box;
      }
    }
    return found;
  }

  private static int count(List<Box> boxes, String type) {
    int n = 0;
    for (Box box : boxes) {
      if (box.type.equals(type)) n++;
    }
    return n;
  }

  private static boolean validSize(int size) {
    return size == 0 || size == 4 || size == 8;
  }

  private static void writeBox(ByteArrayOutputStream out, String type, byte[] payload) {
    writeN(out, 8L + payload.length, 4);
    out.write(type.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
    out.write(payload, 0, payload.length);
  }

  private static void writeN(ByteArrayOutputStream out, long value, int bytes) {
    for (int i = bytes - 1; i >= 0; i--) out.write((int) (value >>> (8 * i)));
  }

  private static void putN(byte[] target, int at, long value, int bytes) {
    for (int i = 0; i < bytes; i++) target[at + i] = (byte) (value >>> (8 * (bytes - 1 - i)));
  }
}
