package cn.yooss.heic.thumbnail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThumbnailLoaderTest {
  /** Runs submitted tasks only when asked to. */
  private static final class ManualExecutor implements java.util.concurrent.Executor {
    final Queue<Runnable> queue = new ArrayDeque<>();
    boolean reject;

    @Override
    public void execute(Runnable command) {
      if (reject) throw new RejectedExecutionException();
      queue.add(command);
    }

    int runAll() {
      int n = 0;
      while (!queue.isEmpty()) {
        queue.poll().run();
        n++;
      }
      return n;
    }
  }

  private final ManualExecutor executor = new ManualExecutor();
  private final List<String> failures = new ArrayList<>();
  private final ThumbnailLoader<String, String> loader =
      new ThumbnailLoader<>(executor, 3, (key, error) -> failures.add(key + ": " + error.getMessage()));
  private final AtomicInteger loads = new AtomicInteger();
  private final List<String> loaded = new ArrayList<>();

  private String get(String key) {
    return get(key, () -> {
      loads.incrementAndGet();
      return "icon:" + key;
    });
  }

  private String get(String key, Callable<String> load) {
    return loader.get(key, load, () -> loaded.add(key));
  }

  @Test
  void loadsAsynchronouslyOnceAndCaches() {
    assertNull(get("a"), "never computed on the calling thread");
    assertNull(get("a"));
    assertEquals(ThumbnailLoader.State.LOADING, loader.state("a"));
    assertEquals(1, executor.queue.size(), "requests for the same key are deduplicated");
    assertEquals(0, loads.get());

    assertEquals(1, executor.runAll());
    assertEquals(1, loads.get());
    assertEquals(List.of("a"), loaded, "notified after the value was cached");
    assertEquals(ThumbnailLoader.State.LOADED, loader.state("a"));
    assertEquals("icon:a", get("a"));
    assertEquals(0, executor.queue.size(), "a cached value is not loaded again");
  }

  @Test
  void valueIsCachedBeforeTheCallbackRuns() {
    List<String> seenInCallback = new ArrayList<>();
    loader.get("a", () -> "icon:a", () -> seenInCallback.add(loader.get("a", () -> "other", () -> {})));
    executor.runAll();
    assertEquals(List.of("icon:a"), seenInCallback);
  }

  @Test
  void failuresAreCachedAndNotRetriedForTheSameKey() {
    assertNull(get("broken", () -> {
      loads.incrementAndGet();
      throw new IOException("bad data");
    }));
    executor.runAll();
    assertEquals(ThumbnailLoader.State.FAILED, loader.state("broken"));
    assertEquals(List.of("broken: bad data"), failures);
    assertEquals(List.of(), loaded, "no refresh for a failure: the default icon is already shown");

    assertNull(get("broken"));
    assertEquals(0, executor.queue.size(), "not retried");
    assertEquals(1, loads.get());
  }

  @Test
  void linkageErrorsCountAsFailures() {
    get("a", () -> {
      throw new UnsatisfiedLinkError("no ImageIO");
    });
    executor.runAll();
    assertEquals(ThumbnailLoader.State.FAILED, loader.state("a"));
  }

  @Test
  void nullResultMeansSkippedAndIsRetriedLater() {
    assertNull(get("a", () -> null));
    executor.runAll();
    assertEquals(ThumbnailLoader.State.ABSENT, loader.state("a"));
    assertEquals(List.of(), loaded);
    assertNull(get("a"));
    assertEquals(1, executor.queue.size(), "scheduled again");
  }

  @Test
  void cacheIsBoundedLeastRecentlyUsedFirst() {
    for (String key : new String[]{"a", "b", "c"}) get(key);
    executor.runAll();
    assertEquals("icon:a", get("a")); // a becomes the most recently used
    get("d");
    executor.runAll();
    assertEquals(3, loader.size());
    assertEquals(ThumbnailLoader.State.ABSENT, loader.state("b"), "b was evicted");
    assertEquals("icon:a", get("a"));
    assertNull(get("b"));
    assertEquals(1, executor.queue.size(), "an evicted key is loaded again");
  }

  @Test
  void clearForgetsValuesAndFailures() {
    get("a");
    get("f", () -> {
      throw new IOException("x");
    });
    executor.runAll();
    loader.clear();
    assertEquals(0, loader.size());
    assertNull(get("a"));
    assertNull(get("f"));
    assertEquals(2, executor.queue.size());
  }

  @Test
  void shutDownStopsSchedulingAndDropsLateResults() {
    get("a");
    executor.runAll();
    get("b"); // queued, not yet run
    loader.shutDown();
    assertTrue(loader.isShutDown());
    assertEquals(0, loader.size());
    assertNull(get("a"), "nothing is returned after shutdown");
    assertNull(get("c"));
    assertEquals(1, executor.queue.size(), "nothing new is submitted");

    executor.runAll(); // the task queued before the shutdown
    assertEquals(0, loader.size(), "late results are not cached");
    assertEquals(List.of("a"), loaded, "and not announced");
    assertEquals(1, loads.get(), "a task that starts after the shutdown does not load");
  }

  @Test
  void rejectedSubmissionIsRetriedOnNextRequest() {
    executor.reject = true;
    assertNull(get("a"));
    assertEquals(ThumbnailLoader.State.ABSENT, loader.state("a"));
    executor.reject = false;
    assertNull(get("a"));
    assertEquals(1, executor.queue.size());
  }

  @Test
  void concurrentRequestsLoadEachKeyOnce() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      AtomicInteger realLoads = new AtomicInteger();
      CountDownLatch done = new CountDownLatch(20);
      ThumbnailLoader<Integer, String> concurrent = new ThumbnailLoader<>(pool, 100, (k, e) -> {});
      Thread[] requesters = new Thread[8];
      for (int t = 0; t < requesters.length; t++) {
        requesters[t] = new Thread(() -> {
          for (int round = 0; round < 200; round++) {
            for (int key = 0; key < 20; key++) {
              concurrent.get(key, () -> {
                realLoads.incrementAndGet();
                Thread.sleep(2);
                return "v";
              }, done::countDown);
            }
          }
        });
        requesters[t].start();
      }
      for (Thread requester : requesters) requester.join();
      assertTrue(done.await(10, TimeUnit.SECONDS));
      assertEquals(20, realLoads.get(), "each key loaded exactly once");
      for (int key = 0; key < 20; key++) assertEquals("v", concurrent.get(key, () -> "x", () -> {}));
    }
    finally {
      pool.shutdownNow();
    }
  }

  @Test
  void failureHandlerReceivesTheException() {
    List<Throwable> errors = new ArrayList<>();
    ThumbnailLoader<String, String> withHandler = new ThumbnailLoader<>(executor, 3, (k, e) -> errors.add(e));
    withHandler.get("a", () -> {
      throw new IllegalStateException("boom");
    }, () -> {});
    executor.runAll();
    assertEquals(1, errors.size());
    assertInstanceOf(IllegalStateException.class, errors.get(0));
  }
}
