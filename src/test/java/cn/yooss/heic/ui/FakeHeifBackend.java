package cn.yooss.heic.ui;

import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifImageInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A backend for the UI tests: probing returns {@link #probeResult}, re-checking {@link #recheckResult}; it counts the
 * probes and records the thread of the last one (the UI must never probe on the EDT). It cannot decode.
 */
final class FakeHeifBackend implements HeifBackend {
  volatile HeifBackendStatus probeResult;
  volatile HeifBackendStatus recheckResult;
  private volatile HeifBackendStatus cached;
  final AtomicInteger probes = new AtomicInteger();
  final AtomicInteger rechecks = new AtomicInteger();
  volatile Thread probeThread;
  /** If set, the next re-check counts {@link #recheckEntered} down and waits (up to 10 s) for this latch. */
  volatile CountDownLatch recheckRelease;
  volatile CountDownLatch recheckEntered;

  /** Probed already: {@link #cachedStatus()} is {@code status} from the start. */
  static FakeHeifBackend probed(@NotNull HeifBackendStatus status) {
    FakeHeifBackend backend = new FakeHeifBackend(status);
    backend.cached = status;
    return backend;
  }

  /** Not probed yet: {@link #cachedStatus()} is {@code null} until {@link #status()} is called. */
  static FakeHeifBackend unprobed(@NotNull HeifBackendStatus status) {
    return new FakeHeifBackend(status);
  }

  private FakeHeifBackend(HeifBackendStatus status) {
    probeResult = status;
    recheckResult = status;
  }

  @Override
  public @NotNull String id() {
    return "fake";
  }

  @Override
  public @NotNull String displayName() {
    return "fake HEIF decoder";
  }

  @Override
  public synchronized @NotNull HeifBackendStatus status() {
    if (cached == null) {
      probes.incrementAndGet();
      probeThread = Thread.currentThread();
      cached = probeResult;
    }
    return cached;
  }

  @Override
  public @Nullable HeifBackendStatus cachedStatus() {
    return cached;
  }

  @Override
  public @NotNull HeifBackendStatus recheckStatus() {
    CountDownLatch release = recheckRelease;
    if (release != null) {
      recheckRelease = null;
      recheckEntered.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    synchronized (this) {
      rechecks.incrementAndGet();
      probeThread = Thread.currentThread();
      cached = recheckResult;
      return cached;
    }
  }

  @Override
  public @NotNull HeifImageInfo readInfo(byte[] data) throws IOException {
    throw new IOException("fake backend: " + status());
  }

  @Override
  public @NotNull BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
    throw new IOException("fake backend: " + status());
  }
}
