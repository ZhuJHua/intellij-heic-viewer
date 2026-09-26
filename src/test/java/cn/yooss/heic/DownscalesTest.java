package cn.yooss.heic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The record of images the heap safety valve decoded smaller, keyed by content (pure Java). */
class DownscalesTest {
  @AfterEach
  void reset() {
    Downscales.setListener(null);
    Downscales.clear();
  }

  @Test
  void recordFindForget() {
    AtomicInteger changes = new AtomicInteger();
    Downscales.setListener(changes::incrementAndGet);
    byte[] data = {1, 2, 3, 4, 5};
    String key = Downscales.key(data.length, Downscales.crc(data));
    assertTrue(Downscales.isEmpty());
    assertNull(Downscales.find(key));
    assertFalse(Downscales.hasLength(5));

    assertTrue(Downscales.recordReduced(data, 16384, 16384, 12150, 12150, true), "news: logged");
    assertFalse(Downscales.recordReduced(data, 16384, 16384, 12150, 12150, true), "the same again: not logged again");
    assertEquals(1, changes.get());
    assertFalse(Downscales.isEmpty());
    assertTrue(Downscales.hasLength(5));
    assertFalse(Downscales.hasLength(6));
    Downscales.Entry entry = Downscales.find(key);
    assertNotNull(entry);
    assertEquals("16384x16384 12150x12150 true", entry.width() + "x" + entry.height() + " " + entry.shownWidth() + "x"
                                                 + entry.shownHeight() + " " + entry.isHeapLimited());
    assertEquals(key, entry.key());

    assertTrue(Downscales.recordReduced(data, 16384, 16384, 9000, 9000, true), "another size is news");
    assertEquals(2, changes.get());

    Downscales.recordFullSize(new byte[]{9, 9, 9, 9, 9}); // other content: nothing to forget
    assertEquals(2, changes.get());
    Downscales.recordFullSize(data);
    assertNull(Downscales.find(key));
    assertTrue(Downscales.isEmpty());
    assertEquals(3, changes.get());
  }

  @Test
  void boundedAndOldestDropped() {
    for (int i = 0; i < Downscales.MAX_ENTRIES + 5; i++) {
      Downscales.recordReduced(new byte[]{(byte) i, (byte) (i >> 8)}, 20000, 20000, 1000, 1000, true);
    }
    assertNull(Downscales.find(Downscales.key(2, Downscales.crc(new byte[]{0, 0}))), "the oldest was dropped");
    byte[] newest = {(byte) (Downscales.MAX_ENTRIES + 4), 0};
    assertNotNull(Downscales.find(Downscales.key(2, Downscales.crc(newest))));
  }

  /** The reader tags a reduced image with its full size; the tag shares the pixels and keeps the type. */
  @Test
  void imagesCarryTheirFullSize() {
    for (int type : new int[]{java.awt.image.BufferedImage.TYPE_INT_RGB, java.awt.image.BufferedImage.TYPE_INT_ARGB}) {
      java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(30, 20, type);
      assertNull(Downscales.fromImage(image), "untagged");
      java.awt.image.BufferedImage tagged = Downscales.tag(image, 3000, 2000, true);
      assertEquals(type, tagged.getType());
      assertEquals(image.getColorModel().getPixelSize(), tagged.getColorModel().getPixelSize(), "the IDE shows 24-bit / 32-bit");
      image.setRGB(1, 1, 0xFF123456);
      assertEquals(0xFF123456, tagged.getRGB(1, 1), "the same pixels");
      Downscales.Entry entry = Downscales.fromImage(tagged);
      assertNotNull(entry);
      assertEquals("3000x2000 30x20 true", entry.width() + "x" + entry.height() + " " + entry.shownWidth() + "x"
                                           + entry.shownHeight() + " " + entry.isHeapLimited());
      Downscales.Entry array = Downscales.fromImage(Downscales.tag(image, 50000, 40000, false));
      assertNotNull(array);
      assertFalse(array.isHeapLimited());
    }
    assertNull(Downscales.fromImage(null));
  }

  @Test
  void aFailingListenerCannotFailADecode() {
    Downscales.setListener(() -> {
      throw new IllegalStateException("UI unavailable");
    });
    assertTrue(Downscales.recordReduced(new byte[]{7}, 50000, 50000, 46340, 46340, false));
  }
}
