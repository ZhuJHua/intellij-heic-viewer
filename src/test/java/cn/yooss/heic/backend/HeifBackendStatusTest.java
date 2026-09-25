package cn.yooss.heic.backend;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure Java, runs on every OS. */
class HeifBackendStatusTest {
  @Test
  void available() {
    HeifBackendStatus status = HeifBackendStatus.available("macOS ImageIO.framework");
    assertTrue(status.isAvailable());
    assertNull(status.reason());
    assertFalse(status.isUserInstallable());
    assertEquals("macOS ImageIO.framework", status.detail());
    assertEquals("AVAILABLE: macOS ImageIO.framework", status.toString());
  }

  @Test
  void unavailableWithInstallHints() {
    HeifBackendStatus status = HeifBackendStatus.unavailable(HeifBackendStatus.Reason.LINUX_LIBHEIF_MISSING, "dlopen failed")
      .withInstallCommand("  sudo apt install libheif1 libheif-plugin-libde265 ")
      .withInstallUrl("https://example.org/libheif");
    assertFalse(status.isAvailable());
    assertTrue(status.isUserInstallable());
    assertEquals(HeifBackendStatus.Reason.LINUX_LIBHEIF_MISSING, status.reason());
    assertEquals("sudo apt install libheif1 libheif-plugin-libde265", status.installCommand(), "trimmed");
    assertEquals("https://example.org/libheif", status.installUrl());
    assertEquals("UNAVAILABLE(LINUX_LIBHEIF_MISSING): dlopen failed [install: https://example.org/libheif] "
                 + "[command: sudo apt install libheif1 libheif-plugin-libde265]", status.toString());
    assertNull(status.withInstallUrl(" ").installUrl(), "blank means none");
  }

  @Test
  void userInstallableReasons() {
    EnumSet<HeifBackendStatus.Reason> installable = EnumSet.noneOf(HeifBackendStatus.Reason.class);
    for (HeifBackendStatus.Reason reason : HeifBackendStatus.Reason.values()) {
      if (reason.isUserInstallable()) installable.add(reason);
      assertEquals("backend.status." + reason.name(), reason.bundleKey());
    }
    assertEquals(EnumSet.of(HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING,
                            HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING,
                            HeifBackendStatus.Reason.LINUX_LIBHEIF_MISSING,
                            HeifBackendStatus.Reason.LINUX_HEVC_PLUGIN_MISSING), installable);
  }

  @Test
  void valueSemantics() {
    HeifBackendStatus a = HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "x").withInstallUrl("u");
    HeifBackendStatus b = HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "x").withInstallUrl("u");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, b.withInstallCommand("c"));
    assertNotEquals(a, HeifBackendStatus.unavailable(HeifBackendStatus.Reason.NOT_IMPLEMENTED, "x").withInstallUrl("u"));
    assertNotEquals(HeifBackendStatus.available("x"), HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "x"));
    // IllegalArgumentException from the @NotNull instrumentation, NullPointerException without it
    assertThrows(RuntimeException.class, () -> HeifBackendStatus.unavailable(null, "x"));
  }
}
