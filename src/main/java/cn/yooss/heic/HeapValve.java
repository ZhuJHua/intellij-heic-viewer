package cn.yooss.heic;

import cn.yooss.heic.backend.PixelPipeline;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToLongFunction;

/**
 * The image reader's heap safety valve. HEIC images are decoded like the IDE's own image viewer decodes PNG and JPEG
 * ({@code IfsUtil}): at full resolution, whatever their size, one image for every zoom level, held while the editor is
 * open (and in a {@code SoftReference} afterwards). Only when decoding an image at full size would likely exhaust the
 * IDE's Java heap is it decoded smaller, at the largest size that fits, and the user is told why (a banner above the
 * image, {@code ui.HeicDownscaleNotificationProvider}; for the diff, idea.log).
 * <p>
 * <b>Rule.</b> Before decoding, the reader estimates the heap an image needs: the larger of the decode's own peak
 * ({@code HeifBackend.decodeHeapBytes}: the result, 4 bytes per pixel, and the backend's strips and planes) and what the
 * IDE needs to paint it the first time ({@code HeapCost.painted}: the image and Java2D's temporary copy of it, 8 bytes
 * per pixel), plus the input data. With {@code max} = {@link Runtime#maxMemory()}:
 * <ol>
 *   <li>An estimate of at most {@code max / 4} is always decoded at full size: 512 MB of the default 2 GB heap, so the
 *   12- and 48-megapixel photos of phones and cameras (about 120 and 410 MB) are shown at full size whatever the IDE
 *   holds, exactly like a PNG of that size.</li>
 *   <li>Otherwise the estimate must fit the <i>allowance</i>
 *   <pre>  min(0.4 * max, max - used - inFlight - 0.3 * max)</pre>
 *   at most 40% of the heap for one image (819 MB of 2 GB: an image of about 100 megapixels; 3.2 GB of 8 GB: about
 *   400 megapixels), and 30% of the heap must stay free afterwards (a fifth for the IDE's own work, and the 10% G1 keeps
 *   in reserve, {@code G1ReservePercent}). {@code used} is the heap in use outside the young generation (see
 *   {@link RuntimeHeap}): live data, but also garbage and soft references a GC could still free, so it errs on the IDE's
 *   side; {@code inFlight} are the estimates of the decodes running at the same time (the two sides of a diff).</li>
 *   <li>An image that does not fit is decoded at the largest size whose estimate fits the allowance, but at least
 *   {@code max / 16} (128 MB of 2 GB: about 13 megapixels, still more than a 4K screen), and never smaller than
 *   {@value #MIN_SIDE} pixels on its longer side.</li>
 * </ol>
 * Measured on macOS (JBR 25, G1) through the reader, in a JVM with 600 MB of live data and garbage being made, like an
 * IDE, the images kept like open editors and a first paint simulated by a copy of each: on a 2 GB heap 12- and
 * 48-megapixel images are decoded at full size, 120, 268 and 576 megapixels at about 75 megapixels, and 20% of the heap
 * can still be allocated afterwards, whereas without the valve the first paint of the 268-megapixel image and the decode
 * of the 576-megapixel one ran out of heap. On an 8 GB heap everything up to 268 megapixels, and a diff of two of them,
 * is decoded at full size, 576 megapixels at 426. In IntelliJ IDEA 2024.1.7 on the default 2 GB heap, a 208-megapixel
 * decode (without the paint copy in the estimate) ran out of heap in that first paint.
 * <p>
 * Independently of the heap, an image must fit a Java {@code int[]} ({@link PixelPipeline#MAX_IMAGE_PIXELS}, about
 * 2.1 gigapixels), the array behind a {@code TYPE_INT_*} {@link java.awt.image.BufferedImage}: a larger image is
 * decoded at the largest size that does instead of failing.
 * <p>
 * Thread-safe. The heap numbers come from a {@link Heap} (tests inject their own).
 */
public final class HeapValve {
  /** A full-size decode that needs at most this share of the maximum heap is always allowed. */
  static final double FULL_SIZE_SHARE = 1.0 / 4;
  /** Otherwise no decode may need more than this share of the maximum heap. */
  static final double MAX_SHARE = 0.4;
  /** The share of the maximum heap that must stay free after a decode. */
  static final double RESERVE_SHARE = 0.3;
  /** A reduced decode may use at least this share of the maximum heap. */
  static final double MIN_SHARE = 1.0 / 16;
  /** A reduced decode is at least this large (longer side), whatever the heap: a 4 MB image. */
  static final int MIN_SIDE = 1024;

  /** The valve of the IDE's own heap. */
  public static final HeapValve RUNTIME = new HeapValve(new RuntimeHeap());

  private final Heap heap;
  /** Estimates of the decodes running now ({@link #reserve}). */
  private final AtomicLong inFlight = new AtomicLong();

  public HeapValve(@NotNull Heap heap) {
    this.heap = heap;
  }

  /** The Java heap the valve compares against. */
  public interface Heap {
    /** The maximum heap ({@link Runtime#maxMemory()}); {@link Long#MAX_VALUE} if unlimited. */
    long max();

    /** The heap in use that a garbage collection is not expected to free right away. */
    long used();
  }

  /** Why a decode is smaller than requested. */
  public enum Limit {
    /** Not reduced: the size the caller asked for. */
    NONE,
    /** Reduced so that the estimate fits the allowance of the heap. */
    HEAP,
    /** Reduced so that the pixels fit a Java {@code int[]}. */
    ARRAY
  }

  /** The size to decode an image at and why. Immutable (a hand-written value class, not a record). */
  public static final class Decision {
    private final int side;
    private final int requestedSide;
    private final long bytes;
    private final long fullSizeBytes;
    private final long allowance;
    private final Limit limit;

    Decision(int side, int requestedSide, long bytes, long fullSizeBytes, long allowance, Limit limit) {
      this.side = side;
      this.requestedSide = requestedSide;
      this.bytes = bytes;
      this.fullSizeBytes = fullSizeBytes;
      this.allowance = allowance;
      this.limit = limit;
    }

    /** The {@code maxPixelSize} to decode with: {@code 0} for full resolution. */
    public int side() {
      return side;
    }

    /** The {@code maxPixelSize} the caller asked for ({@code 0}: full resolution). */
    public int requestedSide() {
      return requestedSide;
    }

    /** The estimated heap of the decode at {@link #side()}. */
    public long bytes() {
      return bytes;
    }

    /** The estimated heap of the decode at the requested size. */
    public long fullSizeBytes() {
      return fullSizeBytes;
    }

    /** The heap the decode was fitted into (for a reduced decode: the allowance, at least the minimum share). */
    public long allowance() {
      return allowance;
    }

    public @NotNull Limit limit() {
      return limit;
    }

    /** Whether the image is decoded smaller than the caller asked for. */
    public boolean isReduced() {
      return limit != Limit.NONE;
    }

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "Decision[side=%d, requested=%d, %s, estimate %d MB (requested size %d MB), allowance %d MB]",
                           side, requestedSide, limit, bytes >> 20, fullSizeBytes >> 20, allowance >> 20);
    }
  }

  /**
   * The heap a decode that is not always allowed may use now: {@code min(0.4 * max, max - used - inFlight - 0.3 * max)};
   * negative when the heap is fuller than that. {@link Long#MAX_VALUE} without a known maximum.
   */
  static long allowance(long max, long used, long inFlight) {
    if (max <= 0 || max == Long.MAX_VALUE) return Long.MAX_VALUE;
    long ceiling = (long) (max * MAX_SHARE);
    long available = max - Math.max(0, used) - Math.max(0, inFlight) - (long) (max * RESERVE_SHARE);
    return Math.min(ceiling, available);
  }

  /** The heap a decode may use now (see the class comment). */
  public long allowance() {
    return allowance(heap.max(), heap.used(), inFlight.get());
  }

  /**
   * Decides the size to decode a {@code width x height} image (display size) at, with the heap as it is now.
   *
   * @param requestedSide the longest side the caller needs ({@code 0}, or at least the longer side: full resolution)
   * @param bytes         the estimated heap of a decode with a {@code maxPixelSize} ({@code 0}: full resolution); larger
   *                      sizes need more, except where a backend changes its method (it is checked, not assumed)
   */
  public @NotNull Decision decide(int width, int height, int requestedSide, @NotNull IntToLongFunction bytes) {
    return decide(width, height, requestedSide, bytes, heap.max(), heap.used(), inFlight.get());
  }

  /** {@link #decide(int, int, int, IntToLongFunction)} with the heap's numbers ({@code max}: {@link Heap#max()}). */
  static @NotNull Decision decide(int width, int height, int requestedSide, @NotNull IntToLongFunction bytes,
                                  long max, long used, long inFlight) {
    int longest = Math.max(1, Math.max(width, height));
    int wanted = requestedSide <= 0 || requestedSide >= longest ? 0 : requestedSide;
    int arraySide = arraySide(width, height);
    int side = wanted;
    Limit limit = Limit.NONE;
    if (arraySide > 0 && (side == 0 || side > arraySide)) {
      side = arraySide;
      limit = Limit.ARRAY;
    }
    long wantedBytes = bytes.applyAsLong(wanted);
    long sideBytes = side == wanted ? wantedBytes : bytes.applyAsLong(side);
    boolean unknown = max <= 0 || max == Long.MAX_VALUE;
    long allowance = allowance(max, used, inFlight);
    if (unknown || sideBytes <= (long) (max * FULL_SIZE_SHARE) || sideBytes <= allowance) {
      return new Decision(side, wanted, sideBytes, wantedBytes, unknown ? Long.MAX_VALUE : Math.max(allowance, sideBytes), limit);
    }

    // Too large for the heap: the largest side whose estimate fits. Binary search on the (nearly) monotonic estimate,
    // then step down until the estimate really fits (a backend may need more for some smaller sizes, e.g. an extra
    // full-size copy before its last scaling step).
    long target = Math.max(allowance, (long) (max * MIN_SHARE));
    int top = Math.max(1, (side == 0 ? longest : side) - 1);
    int lo = 1;
    int hi = top;
    while (lo < hi) {
      int mid = lo + (hi - lo + 1) / 2;
      if (bytes.applyAsLong(mid) <= target) lo = mid;
      else hi = mid - 1;
    }
    int reduced = lo;
    long reducedBytes = bytes.applyAsLong(reduced);
    int smallest = Math.min(MIN_SIDE, top);
    while (reducedBytes > target && reduced > smallest) {
      reduced = Math.max(smallest, reduced - Math.max(1, reduced / 10));
      reducedBytes = bytes.applyAsLong(reduced);
    }
    if (reduced < smallest) {
      reduced = smallest;
      reducedBytes = bytes.applyAsLong(reduced);
    }
    return new Decision(reduced, wanted, reducedBytes, wantedBytes, target, Limit.HEAP);
  }

  /**
   * The largest {@code maxPixelSize} whose result fits a Java {@code int[]} ({@link PixelPipeline#MAX_IMAGE_PIXELS}),
   * or {@code 0} if the full-size image does.
   */
  static int arraySide(int width, int height) {
    if ((long) width * height <= PixelPipeline.MAX_IMAGE_PIXELS) return 0;
    int longest = Math.max(width, height);
    int side = (int) Math.min(longest - 1L, (long) Math.floor(longest * Math.sqrt((double) PixelPipeline.MAX_IMAGE_PIXELS / ((double) width * height))));
    while (side > 1) {
      int[] size = PixelPipeline.targetSize(width, height, side);
      if ((long) size[0] * size[1] <= PixelPipeline.MAX_IMAGE_PIXELS) break;
      side--;
    }
    return Math.max(1, side);
  }

  /**
   * Counts {@code bytes} as in flight until the returned reservation is closed, so that decodes running at the same
   * time (the two sides of a diff) are not both granted the same heap.
   */
  public @NotNull Reservation reserve(long bytes) {
    long amount = Math.max(0, bytes);
    inFlight.addAndGet(amount);
    return new Reservation(inFlight, amount);
  }

  /** Estimates of the decodes in flight (tests). */
  long inFlight() {
    return inFlight.get();
  }

  /** A decode's estimate, counted while the decode runs. */
  public static final class Reservation implements AutoCloseable {
    private final AtomicLong inFlight;
    private long amount;

    Reservation(AtomicLong inFlight, long amount) {
      this.inFlight = inFlight;
      this.amount = amount;
    }

    @Override
    public void close() {
      if (amount != 0) {
        inFlight.addAndGet(-amount);
        amount = 0;
      }
    }
  }

  /**
   * The JVM's heap: {@link Runtime#maxMemory()}, and as {@link #used()} the usage of the heap memory pools except the
   * young generation's (eden, or ZGC's young generation), whose content is mostly garbage or promoted later: the old
   * generation holds the long-lived data, the IDE's caches and every large array (G1 allocates arrays of more than half
   * a region directly there, so a decoded image counts as soon as it exists). Garbage in the old generation counts too,
   * so the estimate errs on the side of the IDE. Without such pools, the total heap in use.
   */
  static final class RuntimeHeap implements Heap {
    @Override
    public long max() {
      return Runtime.getRuntime().maxMemory();
    }

    @Override
    public long used() {
      try {
        long used = 0;
        boolean found = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
          if (pool.getType() != MemoryType.HEAP || !pool.isValid()) continue;
          String name = pool.getName();
          if (name.contains("Eden") || name.contains("Young")) continue;
          MemoryUsage usage = pool.getUsage();
          if (usage == null) continue;
          used += usage.getUsed();
          found = true;
        }
        if (found) return used;
      }
      catch (RuntimeException | LinkageError e) {
        // fall through: the whole heap in use
      }
      Runtime runtime = Runtime.getRuntime();
      return runtime.totalMemory() - runtime.freeMemory();
    }
  }
}
