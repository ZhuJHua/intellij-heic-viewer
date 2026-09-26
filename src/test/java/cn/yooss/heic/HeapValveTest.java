package cn.yooss.heic;

import cn.yooss.heic.backend.HeapCost;
import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.PixelPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.IntToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision math of the heap safety valve, with injected heap numbers (pure Java, every JDK). */
class HeapValveTest {
  private static final long MB = 1L << 20;
  private static final long GB = 1L << 30;

  /**
   * The reader's estimate for an opaque image with the default backend: the image, the copy Java2D makes when it first
   * paints it (8 bytes per pixel), the fixed part and a 10 MB input.
   */
  private static IntToLongFunction estimate(int width, int height) {
    HeifImageInfo info = new HeifImageInfo("public.heic", 1, 0, width, height, 1, 8, false);
    return side -> Math.max(HeapCost.simple(info, side), HeapCost.painted(info, side)) + 10 * MB;
  }

  private static HeapValve.Decision decide(int width, int height, long max, long used) {
    return HeapValve.decide(width, height, 0, estimate(width, height), max, used, 0);
  }

  private static long pixels(int width, int height, HeapValve.Decision decision) {
    int[] size = PixelPipeline.targetSize(width, height, decision.side());
    return (long) size[0] * size[1];
  }

  @Test
  void allowance() {
    // 2 GB: at most 40% (819 MB); 30% must stay free.
    assertEquals((long) (2 * GB * 0.4), HeapValve.allowance(2 * GB, 400 * MB, 0));
    assertEquals(2 * GB - 900 * MB - (long) (2 * GB * 0.3), HeapValve.allowance(2 * GB, 900 * MB, 0));
    assertEquals(2 * GB - 900 * MB - 100 * MB - (long) (2 * GB * 0.3), HeapValve.allowance(2 * GB, 900 * MB, 100 * MB),
                 "decodes in flight count");
    assertTrue(HeapValve.allowance(2 * GB, 1900 * MB, 0) < 0, "a full heap allows nothing");
    assertEquals(Long.MAX_VALUE, HeapValve.allowance(Long.MAX_VALUE, 0, 0), "no maximum");
  }

  /**
   * The photos of phones and cameras are decoded at full size on the default 2 GB heap, however full it is: their estimate
   * with the paint copy (48 MP: 406 MB) is within a quarter of the heap.
   */
  @ParameterizedTest
  @ValueSource(longs = {0, 600, 1200, 1800, 2048})
  void typicalPhotosAreAlwaysFullSizeOnTwoGigabytes(long usedMb) {
    for (int[] size : new int[][]{{4032, 3024}, {3024, 4032}, {5712, 4284}, {8064, 6048}, {8192, 6144}, {6000, 8000}}) {
      HeapValve.Decision decision = decide(size[0], size[1], 2 * GB, usedMb * MB);
      assertEquals(0, decision.side(), size[0] + "x" + size[1] + " with " + usedMb + " MB used: " + decision);
      assertFalse(decision.isReduced());
    }
    // With alpha and a rotation on Windows, the decode of 48 MP needs 9 bytes per pixel (440 MB): still within a quarter.
    assertEquals(0, HeapValve.decide(8064, 6048, 0, side -> 9L * 8064 * 6048 + 24 * MB, 2 * GB, 2 * GB, 0).side());
  }

  @Test
  void largeImagesFitTheAllowance() {
    // 268 MP (16384 x 16384, 1 GB of pixels) on 2 GB with 600 MB in use: 40% of the heap at most, i.e. an image of
    // about 400 MB and its first-paint copy.
    HeapValve.Decision decision = decide(16384, 16384, 2 * GB, 600 * MB);
    assertTrue(decision.isReduced(), decision.toString());
    assertEquals(HeapValve.Limit.HEAP, decision.limit());
    assertTrue(decision.side() > 9000 && decision.side() < 16384, decision.toString());
    assertTrue(decision.bytes() <= (long) (2 * GB * 0.4), decision.toString());
    assertTrue(estimate(16384, 16384).applyAsLong(decision.side() + 1) > decision.allowance(), "the largest that fits");
    assertTrue(decision.fullSizeBytes() > GB);

    // 80 MP (305 MB, 644 MB with the paint copy) fits 2 GB when 600 MB are in use, not when 1400 MB are.
    assertFalse(decide(10000, 8000, 2 * GB, 600 * MB).isReduced());
    HeapValve.Decision busy = decide(10000, 8000, 2 * GB, 1400 * MB);
    assertTrue(busy.isReduced(), busy.toString());
    assertEquals(2 * GB / 16, busy.allowance(), "a reduced decode gets at least 1/16 of the heap");

    // A full heap: the minimum share, never less than 1024 pixels on the longer side.
    HeapValve.Decision full = decide(24000, 24000, 2 * GB, 2 * GB);
    assertTrue(full.bytes() <= 2 * GB / 16, full.toString());
    assertTrue(full.side() >= HeapValve.MIN_SIDE, full.toString());
    HeapValve.Decision tiny = decide(24000, 24000, 64 * MB, 64 * MB);
    assertEquals(HeapValve.MIN_SIDE, tiny.side(), "the fixed part of the estimate alone exceeds 1/16 of 64 MB");
  }

  @Test
  void eightGigabytesShowLargeImagesAtFullSize() {
    for (int[] size : new int[][]{{16384, 16384}, {10000, 12000}, {12000, 16000}}) {
      assertFalse(decide(size[0], size[1], 8 * GB, 600 * MB).isReduced(), size[0] + "x" + size[1]);
    }
    // The second 268 MP image of a diff: 1 GB more in use, still within the allowance.
    assertFalse(decide(16384, 16384, 8 * GB, (600 + 1024) * MB).isReduced());
    // 576 MP needs 2.2 GB, twice with the paint copy: more than 40% of 8 GB.
    HeapValve.Decision large = decide(24000, 24000, 8 * GB, 600 * MB);
    assertTrue(large.isReduced());
    assertTrue(large.side() > 20000, large.toString());
  }

  /** The two sides of a diff are decoded at the same time: the estimate of the first counts for the second. */
  @Test
  void decodesInFlightCount() {
    HeapValve valve = new HeapValve(new FixedHeap(2 * GB, 600 * MB));
    IntToLongFunction bytes = estimate(10000, 8000);
    assertFalse(valve.decide(10000, 8000, 0, bytes).isReduced());
    HeapValve.Reservation first = valve.reserve(bytes.applyAsLong(0));
    try {
      assertEquals(bytes.applyAsLong(0), valve.inFlight());
      assertTrue(valve.decide(10000, 8000, 0, bytes).isReduced(), "the second side must share what is left");
    }
    finally {
      first.close();
    }
    assertEquals(0, valve.inFlight());
    HeapValve.Reservation twice = valve.reserve(10 * MB);
    twice.close();
    twice.close();
    assertEquals(0, valve.inFlight(), "closing twice releases once");
  }

  @Test
  void requestedSizeIsKept() {
    // Subsampling asks for less: never more than asked for.
    HeapValve.Decision half = HeapValve.decide(8064, 6048, 4032, estimate(8064, 6048), 2 * GB, 600 * MB, 0);
    assertEquals(4032, half.side());
    assertFalse(half.isReduced());
    assertEquals(4032, half.requestedSide());
    HeapValve.Decision asked = HeapValve.decide(8064, 6048, 8064, estimate(8064, 6048), 2 * GB, 600 * MB, 0);
    assertEquals(0, asked.side(), "the full size is 0");
  }

  /** The pixels must fit a Java int[] (the array of a TYPE_INT image), whatever the heap. */
  @Test
  void intArrayLimit() {
    assertEquals(0, HeapValve.arraySide(46340, 46340), "2 147 395 600 pixels fit");
    int side = HeapValve.arraySide(50000, 50000);
    assertTrue(side > 0 && side < 50000);
    assertTrue((long) side * side <= PixelPipeline.MAX_IMAGE_PIXELS);
    assertTrue((long) (side + 1) * (side + 1) > PixelPipeline.MAX_IMAGE_PIXELS, "the largest that fits: " + side);
    int wide = HeapValve.arraySide(1_000_000, 5000);
    int[] size = PixelPipeline.targetSize(1_000_000, 5000, wide);
    assertTrue((long) size[0] * size[1] <= PixelPipeline.MAX_IMAGE_PIXELS);

    HeapValve.Decision decision = decide(50000, 50000, 64 * GB, GB); // enough heap: only the array limits
    assertEquals(HeapValve.Limit.ARRAY, decision.limit());
    assertEquals(side, decision.side());
    assertTrue(pixels(50000, 50000, decision) <= PixelPipeline.MAX_IMAGE_PIXELS);
    assertTrue(decision.isReduced());

    HeapValve.Decision both = decide(50000, 50000, 8 * GB, GB); // the heap limits more than the array
    assertEquals(HeapValve.Limit.HEAP, both.limit());
    assertTrue(both.side() < side);
  }

  /** A backend may need more for a smaller size (an extra copy before its last scaling step): the result still fits. */
  @Test
  void nonMonotonicEstimates() {
    long full = 4L * 25000 * 25000;
    IntToLongFunction bytes = side -> side == 0 ? full : side > 12500 ? full + 4L * side * side : 4L * side * side;
    HeapValve.Decision decision = HeapValve.decide(25000, 25000, 0, bytes, 4 * GB, 0, 0);
    assertTrue(decision.isReduced());
    assertTrue(decision.bytes() <= decision.allowance(), decision.toString());
    assertEquals(12500, decision.side(), decision.toString());
  }

  @Test
  void unknownMaximumNeverReduces() {
    assertFalse(decide(24000, 24000, Long.MAX_VALUE, 0).isReduced());
    assertFalse(decide(24000, 24000, 0, 0).isReduced());
  }

  @Test
  void runtimeHeap() {
    HeapValve.RuntimeHeap heap = new HeapValve.RuntimeHeap();
    assertEquals(Runtime.getRuntime().maxMemory(), heap.max());
    long used = heap.used();
    assertTrue(used > 0 && used <= heap.max(), "used " + used);
    assertTrue(HeapValve.RUNTIME.allowance() <= (long) (heap.max() * 0.4));
  }

  /** A heap with fixed numbers. */
  static final class FixedHeap implements HeapValve.Heap {
    private final long max;
    private final long used;

    FixedHeap(long max, long used) {
      this.max = max;
      this.used = used;
    }

    @Override
    public long max() {
      return max;
    }

    @Override
    public long used() {
      return used;
    }
  }
}
