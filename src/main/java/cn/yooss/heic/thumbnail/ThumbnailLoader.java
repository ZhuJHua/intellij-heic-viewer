package cn.yooss.heic.thumbnail;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * Asynchronous, deduplicated loading into a bounded LRU cache (no IDE dependencies).
 * <p>
 * {@link #get} never blocks: it returns the cached value, or {@code null} while the value is being loaded on the
 * executor or after loading it failed. A failure is cached as well, so a broken file is not decoded again and again;
 * a changed file has a different key and is tried again. At most one load per key is queued at a time.
 */
final class ThumbnailLoader<K, V> {
  /** Marker for a key whose load failed. */
  private static final Object FAILED = new Object();

  /** Result of {@link #state}, for diagnostics and tests. */
  enum State {ABSENT, LOADING, LOADED, FAILED}

  private final Executor executor;
  private final LruCache<K, Object> results;
  private final Set<K> loading = ConcurrentHashMap.newKeySet();
  private final BiConsumer<K, Throwable> failureHandler;
  private volatile boolean shutDown;

  /**
   * @param maxEntries     maximum number of cached results (values and failures together)
   * @param failureHandler called on the executor thread when a load throws (for logging)
   */
  ThumbnailLoader(Executor executor, int maxEntries, BiConsumer<K, Throwable> failureHandler) {
    this.executor = executor;
    this.results = new LruCache<>(maxEntries);
    this.failureHandler = failureHandler;
  }

  /**
   * Returns the loaded value for {@code key}, or {@code null} if there is none (yet). When the key is neither cached
   * nor being loaded, {@code load} is submitted to the executor; after it has returned a non-null value and the value
   * has been cached, {@code onLoaded} runs on the executor thread. A {@code null} result means "skipped": nothing is
   * cached and the next {@code get} tries again.
   */
  V get(K key, Callable<? extends V> load, Runnable onLoaded) {
    V value = cached(key);
    if (value != null || shutDown || results.containsKey(key)) return value;
    if (!loading.add(key)) return null;
    // A load that finished between the lookup above and loading.add() must not be repeated.
    if (results.containsKey(key)) {
      loading.remove(key);
      return cached(key);
    }
    try {
      executor.execute(() -> run(key, load, onLoaded));
    }
    catch (RejectedExecutionException e) {
      loading.remove(key);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private V cached(K key) {
    Object result = results.get(key);
    return result == null || result == FAILED ? null : (V) result;
  }

  private void run(K key, Callable<? extends V> load, Runnable onLoaded) {
    boolean loaded = false;
    try {
      if (shutDown) return;
      V value = load.call();
      if (value != null && !shutDown) {
        results.put(key, value);
        loaded = true;
      }
    }
    catch (Exception | LinkageError e) {
      if (!shutDown) {
        results.put(key, FAILED);
        failureHandler.accept(key, e);
      }
    }
    finally {
      loading.remove(key);
    }
    if (loaded) onLoaded.run();
  }

  State state(K key) {
    Object result = results.get(key);
    if (result == FAILED) return State.FAILED;
    if (result != null) return State.LOADED;
    return loading.contains(key) ? State.LOADING : State.ABSENT;
  }

  /** Forgets all cached values and failures (loads in progress still complete and are cached). */
  void clear() {
    results.clear();
  }

  /** Stops loading: nothing is submitted or cached any more, and the cache is emptied. Irreversible. */
  void shutDown() {
    shutDown = true;
    results.clear();
  }

  boolean isShutDown() {
    return shutDown;
  }

  int size() {
    return results.size();
  }
}
