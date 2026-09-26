package cn.yooss.heic.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static cn.yooss.heic.linux.OsReleaseSamples.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code os-release(5)} parsing (pure Java, runs on every OS). */
class LinuxDistributionTest {
  @Test
  void ubuntu() {
    LinuxDistribution d = parse(OsReleaseSamples.UBUNTU_24_04);
    assertEquals("ubuntu", d.id());
    assertEquals(List.of("debian"), d.idLike());
    assertEquals("24.04", d.versionId());
    assertEquals(2404, d.versionNumber());
    assertEquals("Ubuntu 24.04.3 LTS", d.prettyName());
    assertEquals("noble", d.field("UBUNTU_CODENAME"));
    assertTrue(d.isLike("debian"));
    assertTrue(d.isLike("ubuntu"));
    assertFalse(d.isLike("fedora"));
    assertFalse(d.isFlatpak());
    assertFalse(d.isSnap());
  }

  @Test
  void derivativesAndQuoting() {
    LinuxDistribution mint = parse(OsReleaseSamples.MINT_22);
    assertEquals("linuxmint", mint.id());
    assertEquals(List.of("ubuntu", "debian"), mint.idLike());
    assertEquals(2200, mint.versionNumber());
    LinuxDistribution endeavour = parse(OsReleaseSamples.ENDEAVOUROS); // single quotes
    assertEquals("endeavouros", endeavour.id());
    assertTrue(endeavour.isLike("arch"));
    assertEquals("EndeavourOS", endeavour.prettyName());
    LinuxDistribution tumbleweed = parse(OsReleaseSamples.TUMBLEWEED); // a commented-out VERSION
    assertNull(tumbleweed.field("VERSION"));
    assertEquals(2025090100, tumbleweed.versionNumber());
    assertEquals(1506, parse(OsReleaseSamples.LEAP_15_6).versionNumber());
    assertEquals(4200, parse(OsReleaseSamples.FEDORA_42).versionNumber());
    assertEquals("", parse(OsReleaseSamples.FEDORA_42).field("VERSION_CODENAME"));
    assertEquals(-1, parse(OsReleaseSamples.ARCH).versionNumber());
    assertEquals("", parse(OsReleaseSamples.ARCH).versionId());
  }

  @Test
  void escapesCommentsAndGarbage() {
    LinuxDistribution d = parse(String.join("\n",
      "# a comment",
      "",
      "ID=\"my-distro\"",
      "  ID_LIKE=\"Debian  Ubuntu\"  ",
      "PRETTY_NAME=\"My \\\"quoted\\\" \\$HOME \\\\ distro\"",
      "NAME=unquoted value",
      "VERSION_ID='7.1'",
      "not a field",
      "BAD KEY=x",
      "=nokey",
      "WINDOWS=line\r"));
    assertEquals("my-distro", d.id());
    assertEquals(List.of("debian", "ubuntu"), d.idLike());
    assertEquals("My \"quoted\" $HOME \\ distro", d.prettyName());
    assertEquals("unquoted value", d.field("NAME"));
    assertEquals("7.1", d.versionId());
    assertEquals(701, d.versionNumber());
    assertEquals("line", d.field("WINDOWS"));
    assertNull(d.field("BAD KEY"));
  }

  @Test
  void emptyOrMissingFile() {
    LinuxDistribution d = LinuxDistribution.parse("", false, false);
    assertEquals("", d.id());
    assertEquals(List.of(), d.idLike());
    assertEquals(-1, d.versionNumber());
    assertEquals("unknown Linux distribution", d.prettyName());
  }

  @Test
  void sandboxesAndValueSemantics() {
    LinuxDistribution flatpak = LinuxDistribution.parse(OsReleaseSamples.FEDORA_42, true, false);
    assertTrue(flatpak.isFlatpak());
    assertTrue(flatpak.toString().contains("Flatpak"), flatpak.toString());
    assertTrue(LinuxDistribution.parse(OsReleaseSamples.UBUNTU_24_04, false, true).isSnap());
    assertEquals(parse(OsReleaseSamples.ARCH), parse(OsReleaseSamples.ARCH));
    assertEquals(parse(OsReleaseSamples.ARCH).hashCode(), parse(OsReleaseSamples.ARCH).hashCode());
    assertNotEquals(parse(OsReleaseSamples.ARCH), parse(OsReleaseSamples.MANJARO));
    assertNotEquals(flatpak, parse(OsReleaseSamples.FEDORA_42));
    assertEquals("Arch Linux (ID=arch, ID_LIKE=, VERSION_ID=)", parse(OsReleaseSamples.ARCH).toString());
  }

  /** The real file of the Linux machine the tests run on. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void currentSystem() {
    LinuxDistribution d = LinuxDistribution.current();
    System.out.println("os-release: " + d);
    assertFalse(d.id().isEmpty(), d.toString());
  }
}
