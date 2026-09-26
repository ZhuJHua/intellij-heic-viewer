package cn.yooss.heic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The images the {@link HeapValve heap safety valve} decoded smaller than they are, so that the editor banner
 * ({@code ui.HeicDownscaleNotificationProvider}) can say so, in two ways:
 * <ul>
 *   <li>The image itself: the reader {@linkplain #tag tags} a reduced image with its full size as
 *   {@link BufferedImage} properties (JDK values only, so an image the IDE keeps across a reload of the plugin is still
 *   recognized); the banner reads the image an editor shows ({@link #fromImage}).</li>
 *   <li>A record by content, for an editor whose image is not set yet (the IDE hands the decoded image to the editor
 *   after the reader returns) and to log each image once: the reader only sees the bytes of an image ({@code IfsUtil}
 *   hands it a stream of the file's content), so an image is identified by its length and CRC-32. The latest decode
 *   of a content wins: a full-size decode removes its entry. At most {@value #MAX_ENTRIES} entries, the least recently
 *   recorded are dropped. The listener ({@link #setListener}) is told about every change; the UI updates the banners.</li>
 * </ul>
 * Thread-safe.
 */
public final class Downscales {
  static final int MAX_ENTRIES = 64;

  private static final Object LOCK = new Object();
  /** Guarded by {@link #LOCK}; insertion order = recording order. */
  private static final Map<String, Entry> entries = new LinkedHashMap<>();
  private static volatile boolean empty = true;
  private static volatile @Nullable Runnable listener;

  private Downscales() {
  }

  /** One image decoded smaller: its size, the size it is shown at and why. Immutable. */
  public static final class Entry {
    private final String key;
    private final int width;
    private final int height;
    private final int shownWidth;
    private final int shownHeight;
    private final boolean heapLimited;

    Entry(@NotNull String key, int width, int height, int shownWidth, int shownHeight, boolean heapLimited) {
      this.key = key;
      this.width = width;
      this.height = height;
      this.shownWidth = shownWidth;
      this.shownHeight = shownHeight;
      this.heapLimited = heapLimited;
    }

    /** The content's identity ({@link #key(long, long)}). */
    public @NotNull String key() {
      return key;
    }

    public int width() {
      return width;
    }

    public int height() {
      return height;
    }

    public int shownWidth() {
      return shownWidth;
    }

    public int shownHeight() {
      return shownHeight;
    }

    /**
     * {@code true}: reduced to fit the Java heap (more heap shows it at full size); {@code false}: to fit a Java
     * {@code int[]} ({@link HeapValve.Limit#ARRAY}).
     */
    public boolean isHeapLimited() {
      return heapLimited;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Entry)) return false;
      Entry other = (Entry) o;
      return key.equals(other.key) && width == other.width && height == other.height && shownWidth == other.shownWidth
             && shownHeight == other.shownHeight && heapLimited == other.heapLimited;
    }

    @Override
    public int hashCode() {
      return (((key.hashCode() * 31 + width) * 31 + height) * 31 + shownWidth * 7 + shownHeight) * 2 + (heapLimited ? 1 : 0);
    }

    @Override
    public String toString() {
      return "Downscale[" + width + "x" + height + " shown at " + shownWidth + "x" + shownHeight
             + (heapLimited ? " (heap)" : " (int[])") + ", " + key + "]";
    }
  }

  /** {@link BufferedImage} properties of a reduced image: its full width and height ({@link Integer}s), why ({@link String}). */
  static final String WIDTH_PROPERTY = "heic.viewer.fullWidth";
  static final String HEIGHT_PROPERTY = "heic.viewer.fullHeight";
  static final String LIMIT_PROPERTY = "heic.viewer.limit";

  /**
   * {@code image} with its full size {@code width x height} as properties: a new {@link BufferedImage} on the same
   * pixels (no copy), of the same type.
   *
   * @param heapLimited {@code true} to fit the heap, {@code false} to fit an {@code int[]}
   */
  public static @NotNull BufferedImage tag(@NotNull BufferedImage image, int width, int height, boolean heapLimited) {
    Hashtable<String, Object> properties = new Hashtable<>();
    properties.put(WIDTH_PROPERTY, width);
    properties.put(HEIGHT_PROPERTY, height);
    properties.put(LIMIT_PROPERTY, heapLimited ? "heap" : "array");
    return new BufferedImage(image.getColorModel(), image.getRaster(), image.isAlphaPremultiplied(), properties);
  }

  /**
   * What an image {@linkplain #tag tagged} by the reader says: its full size and the size it is shown at, with an empty
   * key; {@code null} for an image that is not reduced (or not from this reader).
   */
  public static @Nullable Entry fromImage(@Nullable BufferedImage image) {
    if (image == null) return null;
    Object width = image.getProperty(WIDTH_PROPERTY, null);
    Object height = image.getProperty(HEIGHT_PROPERTY, null);
    if (!(width instanceof Integer) || !(height instanceof Integer)) return null;
    Object limit = image.getProperty(LIMIT_PROPERTY, null);
    return new Entry("", (Integer) width, (Integer) height, image.getWidth(), image.getHeight(), !"array".equals(limit));
  }

  /** The identity of a content of {@code length} bytes with CRC-32 {@code crc}. */
  public static @NotNull String key(long length, long crc) {
    return length + ":" + Long.toHexString(crc);
  }

  /** CRC-32 of {@code data}. */
  public static long crc(byte @NotNull [] data) {
    CRC32 crc = new CRC32();
    crc.update(data, 0, data.length);
    return crc.getValue();
  }

  /**
   * The valve decoded {@code data} ({@code width x height}) at {@code shownWidth x shownHeight}.
   *
   * @param heapLimited {@code true} to fit the heap, {@code false} to fit an {@code int[]}
   * @return {@code true} if this is news (not recorded with these sizes before), e.g. to log it once per image
   */
  public static boolean recordReduced(byte @NotNull [] data, int width, int height, int shownWidth, int shownHeight,
                                      boolean heapLimited) {
    String key = key(data.length, crc(data));
    Entry entry = new Entry(key, width, height, shownWidth, shownHeight, heapLimited);
    synchronized (LOCK) {
      Entry previous = entries.remove(key);
      entries.put(key, entry);
      Iterator<String> oldest = entries.keySet().iterator();
      while (entries.size() > MAX_ENTRIES) {
        oldest.next();
        oldest.remove();
      }
      empty = false;
      if (entry.equals(previous)) return false;
    }
    changed();
    return true;
  }

  /** {@code data} was decoded at full size: forgets an earlier reduced decode of it. */
  public static void recordFullSize(byte @NotNull [] data) {
    if (empty) return;
    String key = key(data.length, crc(data));
    boolean removed;
    synchronized (LOCK) {
      removed = entries.remove(key) != null;
      empty = entries.isEmpty();
    }
    if (removed) changed();
  }

  /** Whether no image is recorded (a volatile read). */
  public static boolean isEmpty() {
    return empty;
  }

  /** Whether an image of {@code length} bytes is recorded. */
  public static boolean hasLength(long length) {
    if (empty) return false;
    String prefix = length + ":";
    synchronized (LOCK) {
      for (String key : entries.keySet()) {
        if (key.startsWith(prefix)) return true;
      }
    }
    return false;
  }

  /** The entry of the content with this identity ({@link #key(long, long)}), or {@code null}. */
  public static @Nullable Entry find(@NotNull String key) {
    if (empty) return null;
    synchronized (LOCK) {
      return entries.get(key);
    }
  }

  /** {@code listener} runs (on the decoding thread) whenever an entry is added, changed or removed; {@code null}: none. */
  public static void setListener(@Nullable Runnable listener) {
    Downscales.listener = listener;
  }

  private static void changed() {
    Runnable current = listener;
    if (current == null) return;
    try {
      current.run();
    }
    catch (RuntimeException | LinkageError e) {
      // the banner is a convenience: never fail a decode because of it
    }
  }

  /** Forgets everything (tests, and before the plugin is unloaded). */
  public static void clear() {
    synchronized (LOCK) {
      entries.clear();
      empty = true;
    }
  }
}
