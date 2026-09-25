package cn.yooss.heic.backend;

import cn.yooss.heic.Fixtures;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The OS-independent part of the backend contract, with a fake backend. Pure Java, runs on every OS. */
class AbstractHeifBackendTest {
  private static final byte[] HEIC = Fixtures.bytes("rgb_sips.heic");

  /** Records calls; decodes to a 6x4 image; its probe and decode behaviour can be replaced. */
  private static final class FakeBackend extends AbstractHeifBackend {
    final AtomicInteger probes = new AtomicInteger();
    final AtomicInteger decodes = new AtomicInteger();
    volatile Supplier<HeifBackendStatus> probe = () -> HeifBackendStatus.available("fake");
    volatile RuntimeException decodeFailure;

    @Override
    public @NotNull String id() {
      return "fake";
    }

    @Override
    public @NotNull String displayName() {
      return "Fake decoder";
    }

    @Override
    protected @NotNull HeifBackendStatus probe() {
      probes.incrementAndGet();
      return probe.get();
    }

    @Override
    protected @NotNull HeifImageInfo doReadInfo(byte[] data) {
      decodes.incrementAndGet();
      return new HeifImageInfo("heic", 1, 0, 6, 4, 1, 8, false);
    }

    @Override
    protected @NotNull BufferedImage doDecode(byte[] data, int maxPixelSize) {
      decodes.incrementAndGet();
      if (decodeFailure != null) throw decodeFailure;
      return new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
    }
  }

  @Test
  void statusIsProbedOnceAndCached() {
    FakeBackend backend = new FakeBackend();
    assertNull(backend.cachedStatus(), "nothing probed yet");
    assertTrue(backend.status().isAvailable());
    assertSame(backend.status(), backend.status());
    assertEquals(1, backend.probes.get());

    backend.probe = () -> HeifBackendStatus.unavailable(HeifBackendStatus.Reason.LINUX_LIBHEIF_MISSING, "gone");
    assertTrue(backend.status().isAvailable(), "still cached");
    assertEquals(HeifBackendStatus.Reason.LINUX_LIBHEIF_MISSING, backend.recheckStatus().reason());
    assertEquals(HeifBackendStatus.Reason.LINUX_LIBHEIF_MISSING, backend.status().reason());
    assertEquals(2, backend.probes.get());
  }

  @Test
  void failingProbeIsAnErrorStatus() {
    FakeBackend backend = new FakeBackend();
    backend.probe = () -> {
      throw new UnsatisfiedLinkError("libheif.so.1: cannot open shared object file");
    };
    HeifBackendStatus status = backend.status();
    assertEquals(HeifBackendStatus.Reason.ERROR, status.reason());
    assertTrue(status.detail().contains("libheif.so.1"), status.detail());

    backend.probe = () -> null;
    assertEquals(HeifBackendStatus.Reason.ERROR, backend.recheckStatus().reason());
  }

  @Test
  void decodeMethodsCheckTheirInputFirst() {
    FakeBackend backend = new FakeBackend();
    for (byte[] data : new byte[][]{null, new byte[0], Fixtures.bytes("rgb.png"), Fixtures.bytes("rgb.avif"),
                                    Arrays.copyOf(HEIC, HEIC.length - 100)}) {
      assertThrows(IOException.class, () -> backend.readInfo(data));
      assertThrows(IOException.class, () -> backend.decode(data, 0));
      assertThrows(IOException.class, () -> backend.decodeThumbnail(data, 16));
    }
    IOException png = assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb.png"), 0));
    assertEquals(HeifInput.NOT_HEIF, png.getMessage());
    assertEquals(0, backend.decodes.get(), "nothing but HEIF data reaches the decoder");

    assertThrows(IllegalArgumentException.class, () -> backend.decode(HEIC, -1));
    assertThrows(IllegalArgumentException.class, () -> backend.decodeThumbnail(HEIC, 0));
  }

  @Test
  void unavailableBackendDoesNotDecode() {
    FakeBackend backend = new FakeBackend();
    backend.probe = () -> HeifBackendStatus.unavailable(HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING, "no HEVC");
    IOException e = assertThrows(IOException.class, () -> backend.decode(HEIC, 0));
    assertTrue(e.getMessage().startsWith("Fake decoder cannot decode HEIF images: UNAVAILABLE(WINDOWS_HEVC_EXTENSION_MISSING)"),
               e.getMessage());
    assertThrows(IOException.class, () -> backend.readInfo(HEIC));
    assertThrows(IOException.class, () -> backend.decodeThumbnail(HEIC, 16));
    assertEquals(0, backend.decodes.get());

    backend.probe = () -> HeifBackendStatus.available("installed now");
    backend.recheckStatus();
    assertDoesNotThrow(() -> backend.decode(HEIC, 0));
  }

  private static void assertDoesNotThrow(ThrowingRunnable runnable) {
    try {
      runnable.run();
    }
    catch (Exception e) {
      throw new AssertionError("unexpected " + e, e);
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  void nativeFailuresBecomeIOExceptions() {
    FakeBackend backend = new FakeBackend();
    backend.decodeFailure = new IllegalStateException("HRESULT 0x88982F50");
    IOException e = assertThrows(IOException.class, () -> backend.decode(HEIC, 0));
    assertSame(backend.decodeFailure, e.getCause());
    assertTrue(e.getMessage().startsWith("Fake decoder failed"), e.getMessage());
  }

  @Test
  void thumbnailDefaultsToADecodeAtTheRequestedSize() throws IOException {
    FakeBackend backend = new FakeBackend();
    assertEquals(6, backend.decodeThumbnail(HEIC, 16).getWidth());
    assertEquals(1, backend.decodes.get());
    assertEquals("Fake decoder", backend.toString());
  }
}
