package cn.yooss.heic.linux;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifBackends;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The availability probe of the libheif backend: which libraries it tries, and the statuses for a missing libheif, a
 * libheif without HEVC decoder and a working one, each with a clean {@link IOException} when decoding is impossible.
 */
class LibheifHeifBackendTest {
  private static final LinuxDistribution UBUNTU = OsReleaseSamples.parse(OsReleaseSamples.UBUNTU_24_04);

  /** libheif is loaded by its soname, then by JNA's short name. */
  @Test
  void standardLibraryNames() {
    assertEquals(List.of("libheif.so.1", "heif"), LibheifHeifBackend.SYSTEM_LIBRARY_NAMES);
  }

  @Test
  void condensesJnaMessages() {
    assertEquals("Unable to load library 'libheif.so.1': libheif.so.1: cannot open shared object file: No such file",
                 LibheifHeifBackend.condense("Unable to load library 'libheif.so.1':\n  libheif.so.1: cannot open shared "
                                             + "object file: No such file\r\n"));
    assertEquals("?", LibheifHeifBackend.condense(null));
    assertTrue(LibheifHeifBackend.condense("x".repeat(1000)).endsWith("..."));
  }

  /** Nothing to load: LINUX_LIBHEIF_MISSING with what was tried, the install command and a clean IOException. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void missingLibrary() {
    List<String> candidates = List.of("/nonexistent/libheif.so.1", "libheif-that-does-not-exist.so.1");
    LibheifHeifBackend backend = new LibheifHeifBackend(candidates, () -> UBUNTU, false);
    HeifBackendStatus status = backend.status();
    assertEquals(Reason.LINUX_LIBHEIF_MISSING, status.reason(), status.toString());
    assertTrue(status.detail().contains("/nonexistent/libheif.so.1") && status.detail().contains("libheif-that-does-not-exist.so.1"),
               status.detail());
    assertTrue(status.detail().contains("Ubuntu 24.04.3 LTS"), status.detail());
    assertEquals("sudo apt install libheif1 libheif-plugin-libde265", status.installCommand());
    assertEquals(LibheifRemedy.HELP_URL, status.installUrl());
    assertTrue(status.reason().isUserInstallable());
    IOException e = assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb_sips.heic"), 0));
    assertTrue(e.getMessage().contains("LINUX_LIBHEIF_MISSING"), e.getMessage());
    assertThrows(IOException.class, () -> backend.readInfo(Fixtures.bytes("rgb_sips.heic")));
    assertEquals(Reason.LINUX_LIBHEIF_MISSING, backend.recheckStatus().reason());
  }

  /** A library that loads but is not libheif (no heif_* functions) counts as missing, too. */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void notLibheif() {
    String other = HeifBackends.Os.current() == HeifBackends.Os.MAC ? "/usr/lib/libz.1.dylib" : "libz.so.1";
    LibheifHeifBackend backend = new LibheifHeifBackend(List.of(other), () -> UBUNTU, false);
    HeifBackendStatus status = backend.status();
    assertEquals(Reason.LINUX_LIBHEIF_MISSING, status.reason(), status.toString());
    assertTrue(status.detail().contains("heif_"), status.detail());
  }

  /** The backend of the plugin on this machine: available, or missing with the remedy of this distribution. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void systemStatus() {
    HeifBackend backend = HeifBackends.current();
    assertTrue(backend instanceof LibheifHeifBackend, backend.toString());
    HeifBackendStatus status = backend.status();
    System.out.println("libheif backend: " + status);
    LinuxDistribution distro = LinuxDistribution.current();
    if (status.isAvailable()) {
      assertTrue(status.detail().startsWith("libheif "), status.detail());
      return;
    }
    Reason reason = status.reason();
    assertTrue(reason == Reason.LINUX_LIBHEIF_MISSING || reason == Reason.LINUX_HEVC_PLUGIN_MISSING, status.toString());
    assertEquals(LibheifRemedy.installCommand(reason, distro), status.installCommand());
    assertEquals(LibheifRemedy.HELP_URL, status.installUrl());
    if (distro.id().equals("ubuntu") && distro.versionNumber() >= 2404) {
      assertEquals(reason == Reason.LINUX_HEVC_PLUGIN_MISSING ? "sudo apt install libheif-plugin-libde265"
                                                              : "sudo apt install libheif1 libheif-plugin-libde265",
                   status.installCommand());
    }
    IOException e = assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb_sips.heic"), 0));
    assertTrue(e.getMessage().contains(reason.name()), e.getMessage());
  }

  /** A libheif without HEVC decoder (e.g. without libheif-plugin-libde265): the status names the plugin directories. */
  @Test
  @EnabledIf("cn.yooss.heic.linux.TestLibheif#isLoadable")
  void hevcDecoderMissing() {
    HeifBackendStatus status = TestLibheif.status();
    assumeTrue(status.reason() == Reason.LINUX_HEVC_PLUGIN_MISSING, "libheif has an HEVC decoder here");
    assertTrue(status.detail().contains("has no HEVC decoder") && status.detail().contains("plugin directories"), status.detail());
    IOException e = assertThrows(IOException.class, () -> TestLibheif.backend().decode(Fixtures.bytes("rgb_libheif.heic"), 0));
    assertTrue(e.getMessage().contains("LINUX_HEVC_PLUGIN_MISSING"), e.getMessage());
  }

  @Test
  @EnabledIf("cn.yooss.heic.linux.TestLibheif#isAvailable")
  void availableStatus() {
    LibheifHeifBackend backend = TestLibheif.backend();
    HeifBackendStatus status = backend.status();
    assertTrue(status.detail().startsWith("libheif " + backend.library().version()), status.detail());
    assertTrue(status.detail().contains("through the IDE's JNA"), status.detail());
    assertSame(status, backend.status());
    // a recheck reuses the loaded library (heif_init is not called again)
    Libheif lib = backend.library();
    assertTrue(backend.recheckStatus().isAvailable());
    assertSame(lib, backend.library());
    assertNotNull(lib.version());
    assertTrue(lib.comparableVersion() >= 10600, lib.version());
    assertFalse(LibheifHeifBackend.pluginDirectories(lib).contains(""));
  }
}
