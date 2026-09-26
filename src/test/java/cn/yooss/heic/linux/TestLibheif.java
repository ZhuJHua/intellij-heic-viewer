package cn.yooss.heic.linux;

import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackends;

import java.io.File;
import java.util.List;

/**
 * The libheif the {@code linux} package tests run against: {@code -Dheic.test.libheif=<path>} ({@code HEIC_TEST_LIBHEIF}
 * in Gradle), else the system's on Linux, else Homebrew's on macOS (so that the binding can be developed and checked
 * on a Mac: the {@code heif_error} return convention of Apple arm64 is the AArch64 one, Intel Macs use x86-64 System
 * V like Linux). Tests that need it are skipped when there is none.
 */
final class TestLibheif {
  private static final String[] HOMEBREW = {"/opt/homebrew/lib/libheif.1.dylib", "/usr/local/lib/libheif.1.dylib"};
  private static volatile LibheifHeifBackend backend;

  private TestLibheif() {
  }

  /** The libraries to try, or {@code null} if this machine has none to test with. */
  static List<String> candidates() {
    String configured = System.getProperty("heic.test.libheif", "").trim();
    if (!configured.isEmpty()) return List.of(configured);
    if (HeifBackends.Os.current() == HeifBackends.Os.LINUX) {
      return LibheifHeifBackend.SYSTEM_LIBRARY_NAMES;
    }
    if (HeifBackends.Os.current() == HeifBackends.Os.MAC) {
      for (String path : HOMEBREW) {
        if (new File(path).isFile()) return List.of(path);
      }
    }
    return null;
  }

  /** A backend on the test libheif (shared by the tests), or {@code null}. */
  static LibheifHeifBackend backend() {
    LibheifHeifBackend result = backend;
    if (result != null) return result;
    List<String> candidates = candidates();
    if (candidates == null) return null;
    synchronized (TestLibheif.class) {
      if (backend == null) backend = new LibheifHeifBackend(candidates, LinuxDistribution::current, false);
      return backend;
    }
  }

  /** The status of {@link #backend()}, or {@code null} if there is no libheif to test with. */
  static HeifBackendStatus status() {
    LibheifHeifBackend b = backend();
    return b == null ? null : b.status();
  }

  /** Whether a libheif with an HEVC decoder is available for tests (JUnit condition). */
  static boolean isAvailable() {
    HeifBackendStatus status = status();
    return status != null && status.isAvailable();
  }

  /**
   * {@code data} with its {@code ispe} box of {@code width x height} changed to {@code newWidth x newHeight}: a file that
   * declares another size than the image libheif builds (for the decode limit; also used by {@link DistroCheck}).
   */
  static byte[] withIspe(byte[] data, int width, int height, int newWidth, int newHeight) {
    return cn.yooss.heic.Fixtures.withIspe(data, width, height, newWidth, newHeight);
  }

  /** Whether some libheif could be loaded (with or without HEVC decoder; JUnit condition). */
  static boolean isLoadable() {
    HeifBackendStatus status = status();
    return status != null && (status.isAvailable() || status.reason() == HeifBackendStatus.Reason.LINUX_HEVC_PLUGIN_MISSING);
  }
}
