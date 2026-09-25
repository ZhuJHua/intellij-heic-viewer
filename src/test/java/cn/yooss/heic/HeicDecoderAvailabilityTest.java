package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackendStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The install prompt's decisions and texts. Needs the IDE's message bundle machinery (Java 21 bytecode in 261). */
@Tag("platform")
class HeicDecoderAvailabilityTest {
  @Test
  void promptsOnlyForMissingInstallableComponentsThatWereNotDismissed() {
    assertFalse(HeicDecoderAvailability.shouldPrompt(HeifBackendStatus.available("ok"), reason -> false));
    for (HeifBackendStatus.Reason reason : HeifBackendStatus.Reason.values()) {
      HeifBackendStatus status = HeifBackendStatus.unavailable(reason, "test");
      assertEquals(reason.isUserInstallable(), HeicDecoderAvailability.shouldPrompt(status, dismissed -> false), reason.name());
      assertFalse(HeicDecoderAvailability.shouldPrompt(status, dismissed -> dismissed == reason), reason + " dismissed");
    }
    EnumSet<HeifBackendStatus.Reason> dismissedWindows = EnumSet.of(HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING);
    assertTrue(HeicDecoderAvailability.shouldPrompt(
      HeifBackendStatus.unavailable(HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING, "test"), dismissedWindows::contains),
               "dismissed per reason");
  }

  @Test
  void promptContentNamesTheComponentAndTheEscapedCommand() {
    String content = HeicDecoderAvailability.promptContent(
      HeifBackendStatus.unavailable(HeifBackendStatus.Reason.LINUX_HEVC_PLUGIN_MISSING, "no HEVC decoder")
        .withInstallCommand("sudo dnf install libheif && echo '<ok>'"));
    assertTrue(content.contains("libde265"), content);
    assertTrue(content.contains("<code>sudo dnf install libheif &amp;&amp; echo &#39;&lt;ok&gt;&#39;</code>"), content);

    String windows = HeicDecoderAvailability.promptContent(
      HeifBackendStatus.unavailable(HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING, "no WIC decoder")
        .withInstallUrl("ms-windows-store://pdp/?ProductId=9PMMSR1CGPWG"));
    assertTrue(windows.contains("HEIF Image Extension"), windows); // the Microsoft Store title
    assertFalse(windows.contains("<code>"), "no command: " + windows);
  }
}
