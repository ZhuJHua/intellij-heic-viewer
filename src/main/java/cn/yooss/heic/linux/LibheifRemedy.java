package cn.yooss.heic.linux;

import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifRemedies;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * The command that installs a working libheif (with an HEVC decoder) on the user's distribution, offered by the install
 * prompt ({@code HeifBackendStatus.installCommand}). The package names were checked against the distributions'
 * package indexes (September 2026; see README.md, "Linux: libheif"):
 * <table>
 *   <caption>Install commands</caption>
 *   <tr><th>Distribution</th><th>libheif missing</th><th>HEVC decoder missing</th></tr>
 *   <tr><td>Debian 13+, Ubuntu 23.10+ and derivatives (plugins are separate packages; Ubuntu 24.04 updates and 26.04
 *   only suggest {@code libheif-plugin-libde265}, Launchpad bug 2142762)</td>
 *   <td>{@code apt install libheif1 libheif-plugin-libde265}</td><td>{@code apt install libheif-plugin-libde265}</td></tr>
 *   <tr><td>Debian 11/12, Ubuntu 20.04-23.04 (libde265 is linked in)</td><td>{@code apt install libheif1}</td>
 *   <td>as above</td></tr>
 *   <tr><td>Fedora (Fedora's libheif has no HEVC because of patents; RPM Fusion Free has {@code libheif-freeworld})</td>
 *   <td colspan="2">enable RPM Fusion Free, {@code dnf install libheif-freeworld}</td></tr>
 *   <tr><td>RHEL, AlmaLinux, Rocky, CentOS Stream, Oracle Linux (libheif from EPEL, the HEVC plugins from RPM Fusion)</td>
 *   <td colspan="2">enable EPEL, CRB and RPM Fusion Free, {@code dnf install libheif-freeworld}</td></tr>
 *   <tr><td>openSUSE Tumbleweed, Slowroll, Leap (openSUSE's libheif has no HEVC; Packman Essentials has
 *   {@code libheif-HEIF})</td><td colspan="2">add Packman Essentials, {@code zypper install --from packman-essentials
 *   libheif1 libheif-HEIF}</td></tr>
 *   <tr><td>Arch Linux, Manjaro, EndeavourOS, ... ({@code libde265} is a dependency of {@code libheif})</td>
 *   <td colspan="2">{@code pacman -S --needed libheif libde265}</td></tr>
 *   <tr><td>Alpine ({@code libheif-libde265} is a separate package since 3.24, a dependency of {@code libheif})</td>
 *   <td>{@code apk add libheif}</td><td>{@code apk add libheif-libde265}</td></tr>
 *   <tr><td>NixOS (no global library path)</td><td colspan="2">{@code nix-env -iA nixos.libheif.lib} (found in
 *   {@code ~/.nix-profile/lib}), or {@code pkgs.libheif.lib} in {@code environment.systemPackages}</td></tr>
 *   <tr><td>Flatpak, other distributions</td><td colspan="2">none (the README explains the options)</td></tr>
 * </table>
 */
final class LibheifRemedy {
  /** README section that explains the Linux setup ("Learn More", or "Installation Instructions" without a command). */
  static final String HELP_URL = HeifRemedies.LINUX_HELP_URL;

  /** Releases whose libheif has libde265 linked in (no plugin packages yet). */
  private static final Set<String> DEBIAN_CODENAMES_WITHOUT_PLUGINS = Set.of("stretch", "buster", "bullseye", "bookworm");
  private static final Set<String> UBUNTU_CODENAMES_WITHOUT_PLUGINS =
    Set.of("bionic", "focal", "hirsute", "impish", "jammy", "kinetic", "lunar");
  private static final Set<String> ENTERPRISE_LINUX_IDS =
    Set.of("rhel", "centos", "almalinux", "rocky", "ol", "eurolinux", "circle", "navy", "cloudlinux", "virtuozzo");
  private static final String PACKMAN = "https://ftp.gwdg.de/pub/linux/misc/packman/suse/";

  private LibheifRemedy() {
  }

  /** The shell command that fixes {@code reason} on {@code distribution}, or {@code null} if there is none. */
  static @Nullable String installCommand(@NotNull Reason reason, @NotNull LinuxDistribution distribution) {
    boolean hevcOnly = reason == Reason.LINUX_HEVC_PLUGIN_MISSING;
    if (reason != Reason.LINUX_LIBHEIF_MISSING && !hevcOnly) return null;
    if (distribution.isFlatpak()) return null; // the sandbox only sees its runtime's libraries
    String id = distribution.id();
    if (id.equals("nixos")) return "nix-env -iA nixos.libheif.lib";
    if (isDebianFamily(distribution)) {
      if (hevcOnly) return "sudo apt install libheif-plugin-libde265";
      return hasPluginPackages(distribution) ? "sudo apt install libheif1 libheif-plugin-libde265" : "sudo apt install libheif1";
    }
    if (isEnterpriseLinux(distribution)) {
      return "sudo dnf install --nogpgcheck https://dl.fedoraproject.org/pub/epel/epel-release-latest-$(rpm -E %rhel).noarch.rpm"
             + " https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-$(rpm -E %rhel).noarch.rpm"
             + " && sudo /usr/bin/crb enable && sudo dnf install libheif-freeworld";
    }
    if (isFedoraFamily(distribution)) {
      return "sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm"
             + " && sudo dnf install libheif-freeworld";
    }
    String packman = packmanDirectory(distribution);
    if (packman != null) {
      return "sudo zypper addrepo -cfp 90 " + PACKMAN + packman + "/Essentials/ packman-essentials"
             + " && sudo zypper --gpg-auto-import-keys refresh packman-essentials"
             + " && sudo zypper install --from packman-essentials libheif1 libheif-HEIF";
    }
    if (distribution.isLike("arch")) return "sudo pacman -S --needed libheif libde265";
    if (id.equals("alpine")) return hevcOnly ? "sudo apk add libheif-libde265" : "sudo apk add libheif";
    return null;
  }

  /** A note for idea.log about the environment, or {@code null}. */
  static @Nullable String note(@NotNull LinuxDistribution distribution) {
    if (distribution.isFlatpak()) {
      return "The IDE runs as a Flatpak and only sees the libraries of its Flatpak runtime";
    }
    if (distribution.id().equals("nixos")) {
      return "NixOS: libheif is looked for in /run/current-system/sw/lib, ~/.nix-profile/lib and /etc/profiles/per-user";
    }
    return null;
  }

  static boolean isDebianFamily(LinuxDistribution d) {
    return d.isLike("debian") || d.isLike("ubuntu");
  }

  /**
   * Whether the release splits libheif's codecs into {@code libheif-plugin-*} packages (Debian 13+, Ubuntu 23.10+, and
   * unknown or rolling releases, which are newer than those).
   */
  static boolean hasPluginPackages(LinuxDistribution d) {
    String id = d.id();
    int version = d.versionNumber();
    if (id.equals("ubuntu") && version > 0) return version >= 2310;
    if (id.equals("debian") && version > 0) return version >= 1300;
    String ubuntuCodename = lower(d.field("UBUNTU_CODENAME"));
    if (!ubuntuCodename.isEmpty()) return !UBUNTU_CODENAMES_WITHOUT_PLUGINS.contains(ubuntuCodename);
    String debianCodename = lower(d.field("DEBIAN_CODENAME"));
    if (debianCodename.isEmpty() && id.equals("debian")) debianCodename = lower(d.field("VERSION_CODENAME"));
    if (!debianCodename.isEmpty()) return !DEBIAN_CODENAMES_WITHOUT_PLUGINS.contains(debianCodename);
    String codename = lower(d.field("VERSION_CODENAME"));
    return !UBUNTU_CODENAMES_WITHOUT_PLUGINS.contains(codename) && !DEBIAN_CODENAMES_WITHOUT_PLUGINS.contains(codename);
  }

  /** RHEL and its rebuilds: {@code PLATFORM_ID="platform:el9"}, or an enterprise {@code ID}/{@code ID_LIKE}. */
  static boolean isEnterpriseLinux(LinuxDistribution d) {
    String platform = lower(d.field("PLATFORM_ID"));
    if (platform.startsWith("platform:el")) return true;
    if (platform.startsWith("platform:f")) return false;
    if (ENTERPRISE_LINUX_IDS.contains(d.id())) return true;
    return !d.id().equals("fedora") && (d.idLike().contains("rhel") || d.idLike().contains("centos"))
           && !d.idLike().contains("fedora");
  }

  /** Fedora and distributions based on it ({@code PLATFORM_ID="platform:f41"}, or {@code fedora} in {@code ID_LIKE}). */
  static boolean isFedoraFamily(LinuxDistribution d) {
    return lower(d.field("PLATFORM_ID")).startsWith("platform:f") || d.isLike("fedora");
  }

  /** The Packman repository directory for openSUSE, e.g. {@code openSUSE_Tumbleweed}, or {@code null}. */
  static @Nullable String packmanDirectory(LinuxDistribution d) {
    String id = d.id();
    if (id.equals("opensuse-tumbleweed")) return "openSUSE_Tumbleweed";
    if (id.equals("opensuse-slowroll")) return "openSUSE_Slowroll";
    if (id.equals("opensuse-leap") || id.equals("opensuse")) {
      String version = d.versionId();
      return version.matches("\\d+\\.\\d+") ? "openSUSE_Leap_" + version : null;
    }
    return null;
  }

  private static String lower(@Nullable String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
