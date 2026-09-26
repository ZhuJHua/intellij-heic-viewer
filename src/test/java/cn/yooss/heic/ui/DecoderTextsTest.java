package cn.yooss.heic.ui;

import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The texts of the banner and of the balloons, resolved through the IDE's message bundle machinery (Java 21 bytecode in
 * 261, hence "platform"). The banner and the balloons themselves are tested in a light IDE,
 * {@link DecoderUiPlatformIntegrationTest}.
 */
@Tag("platform")
class DecoderTextsTest {
  @ParameterizedTest
  @EnumSource(Reason.class)
  void everyReasonHasBannerAndBalloonTexts(Reason reason) {
    HeifRemedy remedy = HeifRemedies.forStatus(HeifBackendStatus.unavailable(reason, "test"));
    String banner = HeicDecoderNotificationProvider.text(remedy);
    assertFalse(banner.contains("<"), "plain text (ends with ... when the editor is narrow): " + banner);
    assertTrue(banner.startsWith(cn.yooss.heic.HeicBundle.message(remedy.titleKey())), banner);
    String content = DecoderPrompt.content(remedy);
    assertFalse(content.trim().isEmpty(), reason.name());
    if (remedy.command() != null) {
      assertTrue(banner.endsWith(remedy.command()), banner);
      assertTrue(content.contains("<code>" + remedy.command() + "</code>"), content);
    }
  }

  /** The command is in the banner where copying it is the remedy (Linux), not where the Store comes first (Windows). */
  @Test
  void bannerShowsTheCommandOnlyWhereItIsTheRemedy() {
    HeifRemedy linux = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.LINUX_LIBHEIF_MISSING, "no libheif")
                                                .withInstallCommand("sudo pacman -S --needed libheif libde265"));
    assertNotNull(linux);
    assertTrue(HeicDecoderNotificationProvider.text(linux).endsWith("sudo pacman -S --needed libheif libde265"));

    String winget = "winget install --id 9PMMSR1CGPWG --source msstore --accept-package-agreements";
    HeifRemedy windows = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.WINDOWS_HEIF_EXTENSION_MISSING, "no WIC decoder")
                                                  .withInstallUrl("ms-windows-store://pdp/?ProductId=9PMMSR1CGPWG")
                                                  .withInstallCommand(winget));
    assertNotNull(windows);
    assertEquals(cn.yooss.heic.HeicBundle.message(windows.titleKey()), HeicDecoderNotificationProvider.text(windows));
    assertTrue(DecoderPrompt.content(windows).contains("<code>" + winget + "</code>"), "the balloon and the tooltip have it");
  }

  @Test
  void commandsAreEscaped() {
    HeifRemedy remedy = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.LINUX_HEVC_PLUGIN_MISSING, "no HEVC decoder")
                                                 .withInstallCommand("sudo dnf install libheif && echo '<ok>'"));
    assertNotNull(remedy);
    String content = DecoderPrompt.content(remedy);
    assertTrue(content.contains("libde265"), content);
    assertTrue(content.contains("<code>sudo dnf install libheif &amp;&amp; echo &#39;&lt;ok&gt;&#39;</code>"), content);
    String banner = HeicDecoderNotificationProvider.text(remedy);
    assertTrue(banner.endsWith("sudo dnf install libheif && echo '<ok>'"), "plain text, as is: " + banner);
  }

  /**
   * A Flatpak IDE: the banner says to run the command in a terminal on the host and shows the long command abbreviated
   * in the middle (the tooltip and the balloon have all of it; Copy Command copies all of it), and the explanation names
   * the runtime's extension rather than the distribution's packages.
   */
  @Test
  void flatpakBannerAndBalloon() {
    String command = "flatpak install flathub org.freedesktop.Platform.codecs-extra//25.08-extra";
    HeifRemedy remedy = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.LINUX_HEVC_PLUGIN_MISSING, "no HEVC decoder")
                                                 .withInstallCommand(command).withInstallUrl(HeifRemedies.LINUX_HELP_URL));
    assertNotNull(remedy);
    String banner = HeicDecoderNotificationProvider.text(remedy);
    assertEquals(cn.yooss.heic.HeicBundle.message(remedy.titleKey())
                 + ". On the host, run: flatpak install \u2026 codecs-extra//25.08-extra", banner);
    assertTrue(banner.length() <= HeicDecoderNotificationProvider.MAX_BANNER_TEXT, banner.length() + ": " + banner);
    assertFalse(banner.contains("distribution"), banner);
    String content = DecoderPrompt.content(remedy);
    assertTrue(content.contains("<code>" + command + "</code>"), content);
    assertTrue(content.contains("codecs-extra") && content.contains("on the host"), content);
    assertFalse(content.contains("libde265 plugin from your distribution"), content);
  }

  @Test
  void longCommandsAreAbbreviatedInTheMiddle() {
    assertEquals("sudo apt install libheif1 libheif-plugin-libde265",
                 HeicDecoderNotificationProvider.abbreviate("sudo apt install libheif1 libheif-plugin-libde265", 60),
                 "short commands are shown as they are");
    String fedora = "sudo dnf install https://mirrors.rpmfusion.org/free/fedora/rpmfusion-free-release-$(rpm -E %fedora).noarch.rpm"
                    + " && sudo dnf install libheif-freeworld";
    String shortened = HeicDecoderNotificationProvider.abbreviate(fedora, 60);
    assertEquals("sudo dnf install \u2026 && sudo dnf install libheif-freeworld", shortened);
    assertTrue(shortened.length() <= 60);
    String zypper = "sudo zypper addrepo -cfp 90 https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/Essentials/"
                    + " packman-essentials && sudo zypper --gpg-auto-import-keys refresh packman-essentials"
                    + " && sudo zypper install --from packman-essentials libheif1 libheif-HEIF";
    String zypperShort = HeicDecoderNotificationProvider.abbreviate(zypper, 60);
    assertTrue(zypperShort.startsWith("sudo zypper addrepo") && zypperShort.endsWith("libheif1 libheif-HEIF"), zypperShort);
    assertTrue(zypperShort.length() <= 60, zypperShort);
    String noSpaces = "x".repeat(100);
    String cut = HeicDecoderNotificationProvider.abbreviate(noSpaces, 60);
    assertTrue(cut.length() <= 60 && cut.contains("\u2026"), cut);
  }

  @Test
  void windowsTextsNameTheExtension() {
    HeifRemedy remedy = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.WINDOWS_HEIF_EXTENSION_MISSING, "no WIC decoder"));
    assertNotNull(remedy);
    String content = DecoderPrompt.content(remedy);
    assertTrue(content.contains("HEIF Image Extension"), content); // the Microsoft Store's title
    assertTrue(content.contains("HEVC Video Extensions"), content);
    assertFalse(content.contains("<code>"), "no command: " + content);
  }
}
