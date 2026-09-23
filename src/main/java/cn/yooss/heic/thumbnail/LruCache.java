package cn.yooss.heic.thumbnail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small thread-safe least-recently-used map: {@link #get} and {@link #put} mark an entry as used, and inserting
 * beyond {@code maxSize} entries evicts the least recently used one.
 */
final class LruCache<K, V> {
  private final int maxSize;
  private final LinkedHashMap<K, V> map;

  LruCache(int maxSize) {
    if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1: " + maxSize);
    this.maxSize = maxSize;
    this.map = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > LruCache.this.maxSize;
      }
    };
  }

  int maxSize() {
    return maxSize;
  }

  synchronized V get(K key) {
    return map.get(key);
  }

  synchronized boolean containsKey(K key) {
    return map.containsKey(key);
  }

  synchronized void put(K key, V value) {
    map.put(key, value);
  }

  synchronized V remove(K key) {
    return map.remove(key);
  }

  synchronized void clear() {
    map.clear();
  }

  synchronized int size() {
    return map.size();
  }

  /** Snapshot of the keys, least recently used first. */
  synchronized List<K> keys() {
    return new ArrayList<>(map.keySet());
  }

  /** Snapshot of the values, least recently used first. */
  synchronized List<V> values() {
    return new ArrayList<>(map.values());
  }
}
