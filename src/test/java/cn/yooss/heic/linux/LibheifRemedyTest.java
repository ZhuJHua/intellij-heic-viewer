package cn.yooss.heic.linux;

import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import org.junit.jupiter.api.Test;

import static cn.yooss.heic.linux.OsReleaseSamples.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The install command per distribution (pure Java, runs on every OS). The package names are documented in
 * {@link LibheifRemedy}; the {@code Linux install commands} CI job runs these commands in containers of the
 * distributions.
 */
class LibheifRemedyTest {
  private static String missing(String osRelease) {
    return LibheifRemedy.installCommand(Reason.LINUX_LIBHEIF_MISSING, parse(osRelease));
  }

  private static String hevc(String osRelease) {
    return LibheifRemedy.installCommand(Reason.LINUX_HEVC_PLUGIN_MISSING, parse(osRelease));
  }

  @Test
  void debianAndUbuntu() {
    // libde265 linked into libheif1
    assertEquals("sudo apt install libheif1", missing(OsReleaseSamples.UBUNTU_22_04));
    assertEquals("sudo apt install libheif1", missing(OsReleaseSamples.DEBIAN_12));
    assertEquals("sudo apt install libheif1", missing(OsReleaseSamples.MINT_21));
    assertEquals("sudo apt install libheif1", missing(OsReleaseSamples.LMDE_6));
    assertEquals("sudo apt install libheif1", missing(OsReleaseSamples.POP_22_04));
    assertEquals("sudo apt install libheif1", missing(OsReleaseSamples.RASPBERRY_PI_OS_12));
    // separate plugin packages (Ubuntu 24.04+ only suggests the HEVC one)
    String withPlugin = "sudo apt install libheif1 libheif-plugin-libde265";
    assertEquals(withPlugin, missing(OsReleaseSamples.UBUNTU_24_04));
    assertEquals(withPlugin, missing(OsReleaseSamples.DEBIAN_13));
    assertEquals(withPlugin, missing(OsReleaseSamples.DEBIAN_TESTING));
    assertEquals(withPlugin, missing(OsReleaseSamples.MINT_22));
    assertEquals(withPlugin, missing(OsReleaseSamples.KALI));
    // a working libheif without HEVC always means a plugin build
    for (String release : new String[]{OsReleaseSamples.UBUNTU_24_04, OsReleaseSamples.UBUNTU_22_04, OsReleaseSamples.DEBIAN_12,
        OsReleaseSamples.MINT_22, OsReleaseSamples.KALI}) {
      assertEquals("sudo apt install libheif-plugin-libde265", hevc(release));
    }
  }

  @Test
  void fedoraNeedsRpmFusion() {
    String fedora = "sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm"
                    + " && sudo dnf install libheif-freeworld";
    assertEquals(fedora, missing(OsReleaseSamples.FEDORA_42));
    assertEquals(fedora, hevc(OsReleaseSamples.FEDORA_42));
    assertEquals(fedora, missing(OsReleaseSamples.NOBARA_42)); // PLATFORM_ID platform:f42 despite ID_LIKE rhel
  }

  @Test
  void enterpriseLinuxNeedsEpelAndRpmFusion() {
    String el = "sudo dnf install --nogpgcheck https://dl.fedoraproject.org/pub/epel/epel-release-latest-$(rpm -E %rhel).noarch.rpm"
                + " https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-$(rpm -E %rhel).noarch.rpm"
                + " && sudo /usr/bin/crb enable && sudo dnf install libheif-freeworld";
    for (String release : new String[]{OsReleaseSamples.ALMALINUX_9, OsReleaseSamples.ROCKY_9, OsReleaseSamples.RHEL_9,
        OsReleaseSamples.CENTOS_STREAM_10}) {
      assertEquals(el, missing(release), release);
      assertEquals(el, hevc(release), release);
    }
  }

  @Test
  void openSuseNeedsPackman() {
    assertEquals("sudo zypper addrepo -cfp 90 https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/"
                 + " packman-essentials && sudo zypper --gpg-auto-import-keys refresh packman-essentials"
                 + " && sudo zypper install --from packman-essentials libheif1 libheif-HEIF",
                 missing(OsReleaseSamples.TUMBLEWEED));
    assertTrue(missing(OsReleaseSamples.SLOWROLL).contains("/openSUSE_Slowroll/Essentials/"));
    assertTrue(hevc(OsReleaseSamples.LEAP_15_6).contains("/openSUSE_Leap_15.6/Essentials/"));
    assertNull(missing(OsReleaseSamples.SLES_15));
  }

  @Test
  void archAlpineNixos() {
    for (String release : new String[]{OsReleaseSamples.ARCH, OsReleaseSamples.MANJARO, OsReleaseSamples.ENDEAVOUROS}) {
      assertEquals("sudo pacman -S --needed libheif libde265", missing(release));
      assertEquals("sudo pacman -S --needed libheif libde265", hevc(release));
    }
    assertEquals("sudo apk add libheif", missing(OsReleaseSamples.ALPINE_3_22));
    assertEquals("sudo apk add libheif-libde265", hevc(OsReleaseSamples.ALPINE_3_22));
    assertEquals("nix-env -iA nixos.libheif.lib", missing(OsReleaseSamples.NIXOS));
    assertTrue(LibheifRemedy.note(parse(OsReleaseSamples.NIXOS)).contains("/run/current-system/sw/lib"));
  }

  @Test
  void noCommandWhereThereIsNone() {
    assertNull(missing(OsReleaseSamples.GENTOO));
    assertNull(missing(""));
    assertNull(LibheifRemedy.installCommand(Reason.LINUX_LIBHEIF_MISSING,
                                            LinuxDistribution.parse(OsReleaseSamples.UBUNTU_24_04, true, false)));
    assertTrue(LibheifRemedy.note(LinuxDistribution.parse(OsReleaseSamples.UBUNTU_24_04, true, false)).contains("Flatpak"));
    assertNull(LibheifRemedy.note(parse(OsReleaseSamples.UBUNTU_24_04)));
    // Snaps of JetBrains IDEs use classic confinement: the system's packages apply
    assertEquals("sudo apt install libheif1 libheif-plugin-libde265",
                 LibheifRemedy.installCommand(Reason.LINUX_LIBHEIF_MISSING, LinuxDistribution.parse(OsReleaseSamples.UBUNTU_24_04, false, true)));
    for (Reason reason : Reason.values()) {
      if (reason != Reason.LINUX_LIBHEIF_MISSING && reason != Reason.LINUX_HEVC_PLUGIN_MISSING) {
        assertNull(LibheifRemedy.installCommand(reason, parse(OsReleaseSamples.UBUNTU_24_04)), reason.name());
      }
    }
  }

  @Test
  void helpUrlPointsAtTheReadme() {
    assertTrue(LibheifRemedy.HELP_URL.startsWith("https://github.com/ZhuJHua/intellij-heic-viewer/"), LibheifRemedy.HELP_URL);
    assertTrue(LibheifRemedy.HELP_URL.endsWith("#linux-libheif"), LibheifRemedy.HELP_URL);
  }
}
