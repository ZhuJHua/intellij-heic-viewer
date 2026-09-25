package cn.yooss.heic.backend;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.linux.LibheifHeifBackend;
import cn.yooss.heic.mac.MacHeifBackend;
import cn.yooss.heic.win.WicHeifBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Backend selection by OS. Pure Java: creating a backend never loads native code, so this runs on every OS. */
class HeifBackendsTest {
  @ParameterizedTest
  @CsvSource({
      "Mac OS X, MAC", "macOS, MAC", "Darwin, MAC", "Windows 11, WINDOWS", "Windows Server 2025, WINDOWS",
      "Linux, LINUX", "FreeBSD, OTHER", "SunOS, OTHER", "'', OTHER",
  })
  void osFromName(String osName, HeifBackends.Os expected) {
    assertEquals(expected, HeifBackends.Os.of(osName));
  }

  @Test
  void backendPerOs() {
    assertInstanceOf(MacHeifBackend.class, HeifBackends.create(HeifBackends.Os.MAC));
    assertInstanceOf(WicHeifBackend.class, HeifBackends.create(HeifBackends.Os.WINDOWS));
    assertInstanceOf(LibheifHeifBackend.class, HeifBackends.create(HeifBackends.Os.LINUX));
    HeifBackend other = HeifBackends.create(HeifBackends.Os.OTHER);
    assertInstanceOf(UnavailableHeifBackend.class, other);
    assertEquals(HeifBackendStatus.Reason.UNSUPPORTED_OS, other.status().reason());
    assertEquals("macos-imageio", HeifBackends.create(HeifBackends.Os.MAC).id());
    assertEquals("windows-wic", HeifBackends.create(HeifBackends.Os.WINDOWS).id());
    assertEquals("linux-libheif", HeifBackends.create(HeifBackends.Os.LINUX).id());
  }

  @Test
  void currentIsTheBackendOfThisOs() {
    HeifBackend current = HeifBackends.current();
    assertSame(current, HeifBackends.current(), "one instance per class loader");
    assertEquals(HeifBackends.create(HeifBackends.Os.current()).getClass(), current.getClass());
  }

  /** The macOS backend only answers "available" on macOS (x86_64 or aarch64). */
  @Test
  void macBackendProbe() {
    HeifBackendStatus status = new MacHeifBackend().status();
    if (HeifBackends.Os.current() == HeifBackends.Os.MAC) {
      assertTrue(status.isAvailable(), status.toString());
    }
    else {
      assertEquals(HeifBackendStatus.Reason.UNSUPPORTED_OS, status.reason(), status.toString());
    }
  }

  /** The Windows backend only probes the system on Windows (x64 or arm64); see WicProbeTest. */
  @Test
  void windowsBackendProbeElsewhere() {
    assumeFalse(HeifBackends.Os.current() == HeifBackends.Os.WINDOWS, "runs on Windows");
    HeifBackendStatus status = new WicHeifBackend().status();
    assertEquals(HeifBackendStatus.Reason.UNSUPPORTED_OS, status.reason(), status.toString());
  }

  /** Placeholders until the Linux backend is implemented. */
  @ParameterizedTest
  @EnumSource(value = HeifBackends.Os.class, names = {"LINUX"})
  void placeholderBackends(HeifBackends.Os os) {
    HeifBackend backend = HeifBackends.create(os);
    assertEquals(HeifBackendStatus.Reason.NOT_IMPLEMENTED, backend.status().reason(), backend.status().toString());
    IOException notHeif = assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb.png"), 0));
    assertEquals(HeifInput.NOT_HEIF, notHeif.getMessage(), "the input check comes first");
    IOException unavailable = assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb_sips.heic"), 0));
    assertTrue(unavailable.getMessage().contains("NOT_IMPLEMENTED"), unavailable.getMessage());
    assertThrows(IOException.class, () -> backend.readInfo(Fixtures.bytes("rgb_sips.heic")));
    assertThrows(IOException.class, () -> backend.decodeThumbnail(Fixtures.bytes("rgb_sips.heic"), 64));
  }

  @Test
  void debugBackend() {
    assertNull(HeifBackends.debugBackend(null));
    assertNull(HeifBackends.debugBackend(" "));
    assertNull(HeifBackends.debugBackend("NO_SUCH_REASON"));

    HeifBackend backend = HeifBackends.debugBackend("LINUX_LIBHEIF_MISSING||sudo apt install libheif1");
    assertNotNull(backend);
    HeifBackendStatus status = backend.status();
    assertEquals(HeifBackendStatus.Reason.LINUX_LIBHEIF_MISSING, status.reason());
    assertNull(status.installUrl());
    assertEquals("sudo apt install libheif1", status.installCommand());

    HeifBackendStatus windows = HeifBackends.debugBackend("WINDOWS_HEIF_EXTENSION_MISSING|ms-windows-store://pdp/?ProductId=9PMMSR1CGPWG").status();
    assertEquals("ms-windows-store://pdp/?ProductId=9PMMSR1CGPWG", windows.installUrl());
    assertNull(windows.installCommand());
  }
}
