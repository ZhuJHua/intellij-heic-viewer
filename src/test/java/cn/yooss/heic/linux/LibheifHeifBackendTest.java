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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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

  @Test
  void systemLibraryNamesByDefault() {
    List<String> candidates = LibheifHeifBackend.libraryCandidates("", UBUNTU);
    assertEquals(List.of("libheif.so.1", "heif"), candidates);
    assertEquals(candidates, LibheifHeifBackend.libraryCandidates("  ", UBUNTU));
  }

  @Test
  void configuredPathReplacesTheSystemSearch(@TempDir Path directory) throws IOException {
    assertEquals(List.of("/opt/libheif/lib/libheif.so.1.19.8"),
                 LibheifHeifBackend.libraryCandidates(" /opt/libheif/lib/libheif.so.1.19.8 ", UBUNTU));
    assertEquals(List.of(System.getProperty("user.home") + "/libheif/libheif.so.1"),
                 LibheifHeifBackend.libraryCandidates("~/libheif/libheif.so.1", UBUNTU));
    Files.createDirectories(directory);
    assertEquals(List.of(new File(directory.toFile(), "libheif.so.1").getPath(), new File(directory.toFile(), "libheif.so").getPath()),
                 LibheifHeifBackend.libraryCandidates(directory.toString(), UBUNTU));
  }

  /** NixOS has no global library path: profiles are added, but only where a libheif.so.1 exists. */
  @Test
  void nixosProfiles() {
    List<String> candidates = LibheifHeifBackend.libraryCandidates("", OsReleaseSamples.parse(OsReleaseSamples.NIXOS));
    assertEquals(List.of("libheif.so.1", "heif"), candidates.subList(0, 2));
    for (String candidate : candidates.subList(2, candidates.size())) {
      assertTrue(new File(candidate).exists(), candidate);
    }
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
    LibheifHeifBackend backend = new LibheifHeifBackend(() -> candidates, () -> UBUNTU, false);
    HeifBackendStatus status = backend.status();
    assertEquals(Reason.LINUX_LIBHEIF_MISSING, status.reason(), status.toString());
    assertTrue(status.detail().contains("/nonexistent/libheif.so.1") && status.detail().contains("libheif-that-does-not-exist.so.1"),
               status.detail());
    assertTrue(status.detail().contains("Ubuntu 24.04.3 LTS"), status.detail());
    assertEquals("sudo apt install libheif1 libheif-plugin-libde265", status.installCommand());
    assertEquals(LibheifRemedy.HELP_URL, status.installUrl());
    assertTrue(status.isUserInstallable());
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
    LibheifHeifBackend backend = new LibheifHeifBackend(() -> List.of(other), () -> UBUNTU, false);
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

  /** A libheif without HEVC decoder (CI: Ubuntu 24.04 without libheif-plugin-libde265): the plugin directory is named. */
  @Test
  @EnabledIf("cn.yooss.heic.linux.TestLibheif#isLoadable")
  void hevcDecoderMissing() {
    HeifBackendStatus status = TestLibheif.status();
    assumeTrue(status.reason() == Reason.LINUX_HEVC_PLUGIN_MISSING, "libheif has an HEVC decoder here");
    assertTrue(status.detail().contains("has no HEVC decoder") && status.detail().contains("plugin directories"), status.detail());
    IOException e = assertThrows(IOException.class, () -> TestLibheif.backend().decode(Fixtures.bytes("rgb_libheif.heic"), 0));
    assertTrue(e.getMessage().contains("LINUX_HEVC_PLUGIN_MISSING"), e.getMessage());
  }

  /**
   * Once a libheif is mapped in the process, a configured libheif that is another file is never opened: JNA opens
   * libraries with RTLD_GLOBAL, so a second libheif would bind its plugins and its own functions to the first one and
   * mix the objects of two versions. The loaded one stays in use, and the detail says that the configured one is used
   * after a restart. The same file under another name is opened (dlopen returns the loaded library).
   */
  @Test
  @EnabledIf("cn.yooss.heic.linux.TestLibheif#isLoadable")
  void aSecondLibheifIsNeverOpenedNextToTheLoadedOne(@TempDir Path directory) throws IOException {
    Path mapped = Files.createFile(directory.resolve("libheif.so.1")); // stands for the libheif mapped in the process
    Path other = Files.createFile(Files.createDirectories(directory.resolve("other")).resolve("libheif.so.1"));
    Path link = Files.createSymbolicLink(directory.resolve("libheif-link.so.1"), mapped);
    String testLibrary = TestLibheif.candidates().get(0);
    List<String> opened = new ArrayList<>();
    Function<String, Libheif> opener = name -> {
      opened.add(name);
      if (name.equals(mapped.toString()) || name.equals(link.toString())) return Libheif.open(testLibrary);
      throw new UnsatisfiedLinkError("a second libheif must not be opened: " + name);
    };

    LibheifHeifBackend backend = new LibheifHeifBackend(() -> List.of(other.toString()), () -> UBUNTU, false, opener, () -> mapped);
    HeifBackendStatus status = backend.status();
    assertEquals(List.of(mapped.toString()), opened, "only the loaded library itself");
    assertTrue(status.isAvailable() || status.reason() == Reason.LINUX_HEVC_PLUGIN_MISSING, status.toString());
    assertTrue(status.detail().contains(other + " is used after an IDE restart"), status.detail());
    Libheif lib = backend.library();
    assertNotNull(lib);
    HeifBackendStatus again = backend.recheckStatus(); // "Check Again": the same library, not opened again
    assertSame(lib, backend.library());
    assertEquals(1, opened.size(), opened.toString());
    assertTrue(again.detail().contains("is used after an IDE restart"), again.detail());

    opened.clear();
    LibheifHeifBackend sameFile = new LibheifHeifBackend(() -> List.of(link.toString()), () -> UBUNTU, false, opener, () -> mapped);
    HeifBackendStatus sameStatus = sameFile.status();
    assertEquals(List.of(link.toString()), opened);
    assertFalse(sameStatus.detail().contains("IDE restart"), sameStatus.detail());

    // Nothing mapped yet (the first load in the process): the configured path is opened.
    opened.clear();
    LibheifHeifBackend first = new LibheifHeifBackend(() -> List.of(other.toString()), () -> UBUNTU, false, opener, () -> null);
    assertEquals(Reason.LINUX_LIBHEIF_MISSING, first.status().reason());
    assertEquals(List.of(other.toString()), opened);
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
