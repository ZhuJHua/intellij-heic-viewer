package cn.yooss.heic.thumbnail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LruCacheTest {
  @Test
  void evictsLeastRecentlyUsedEntry() {
    LruCache<String, Integer> cache = new LruCache<>(3);
    cache.put("a", 1);
    cache.put("b", 2);
    cache.put("c", 3);
    assertEquals(1, cache.get("a")); // a is now the most recently used
    cache.put("d", 4);               // evicts b
    assertEquals(List.of("c", "a", "d"), cache.keys());
    assertNull(cache.get("b"));
    assertEquals(3, cache.size());
    assertEquals(List.of(3, 1, 4), cache.values());
  }

  @Test
  void putOfExistingKeyReplacesAndRefreshes() {
    LruCache<String, Integer> cache = new LruCache<>(2);
    cache.put("a", 1);
    cache.put("b", 2);
    cache.put("a", 10);
    cache.put("c", 3); // evicts b, not a
    assertEquals(10, cache.get("a"));
    assertFalse(cache.containsKey("b"));
    assertTrue(cache.containsKey("c"));
  }

  @Test
  void containsKeyDoesNotChangeTheOrder() {
    LruCache<String, Integer> cache = new LruCache<>(2);
    cache.put("a", 1);
    cache.put("b", 2);
    assertTrue(cache.containsKey("a"));
    cache.put("c", 3); // a is still the eldest
    assertEquals(List.of("b", "c"), cache.keys());
  }

  @Test
  void removeAndClear() {
    LruCache<String, Integer> cache = new LruCache<>(5);
    cache.put("a", 1);
    cache.put("b", 2);
    assertEquals(1, cache.remove("a"));
    assertNull(cache.remove("a"));
    assertEquals(1, cache.size());
    cache.clear();
    assertEquals(0, cache.size());
    assertEquals(List.of(), cache.keys());
  }

  @Test
  void boundIsEnforcedUnderConcurrentUse() throws InterruptedException {
    LruCache<Integer, Integer> cache = new LruCache<>(500);
    Thread[] threads = new Thread[4];
    for (int t = 0; t < threads.length; t++) {
      int offset = t * 10_000;
      threads[t] = new Thread(() -> {
        for (int i = 0; i < 5_000; i++) {
          cache.put(offset + i, i);
          cache.get(offset + i / 2);
        }
      });
      threads[t].start();
    }
    for (Thread thread : threads) thread.join();
    assertEquals(500, cache.size());
    assertEquals(500, cache.maxSize());
  }

  @Test
  void rejectsInvalidSize() {
    assertThrows(IllegalArgumentException.class, () -> new LruCache<>(0));
  }
}
