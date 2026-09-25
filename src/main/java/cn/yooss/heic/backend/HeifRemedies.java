package cn.yooss.heic.backend;

import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifRemedy.Action;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The remedies of every {@link Reason}: which page to open, which command to copy, what else to offer. The texts are in
 * {@code messages/HeicBundle.properties} ({@code remedy.title.<REASON>}, {@code backend.status.<REASON>},
 * {@code remedy.action.*}) and its {@code zh_CN} translation.
 * <p>
 * A backend knows the details of the running system better than these defaults: {@link HeifBackendStatus#installUrl()}
 * (e.g. a Store link for this Windows edition) replaces the default install page and
 * {@link HeifBackendStatus#installCommand()} (e.g. the package command of the detected Linux distribution) replaces the
 * default command. Values that are not {@linkplain #isAllowedUrl allowed URLs} or {@linkplain #isAllowedCommand
 * single-line commands} are ignored, so a backend can never make the IDE open another URL scheme.
 * <p>
 * <b>Integration:</b> the constants marked {@code TODO(windows backend)} and {@code TODO(linux backend)} are placeholders
 * to be replaced by the data the backends verified (Store product ids, package names per distribution).
 * {@code HeifRemediesTest} checks every remedy (texts in both bundles, URL schemes, commands).
 */
public final class HeifRemedies {
  /** The project page; the README's "Requirements" section explains what each OS needs. */
  public static final String PROJECT_URL = "https://github.com/ZhuJHua/intellij-heic-viewer";
  static final String REQUIREMENTS_URL = PROJECT_URL + "#requirements";
  static final String ISSUES_URL = PROJECT_URL + "/issues";

  // TODO(windows backend): verify the product ids of the "HEIF Image Extensions" and the "HEVC Video Extensions" (paid;
  // the free "from Device Manufacturer" edition is 9N4WGH0Z6VHQ) and whether the backend passes a better link through
  // HeifBackendStatus.withInstallUrl (e.g. for Windows 10, arm64, LTSC/Server without the Store).
  static final String HEIF_EXTENSION_STORE_URL = "ms-windows-store://pdp/?ProductId=9PMMSR1CGPWG";
  static final String HEIF_EXTENSION_WEB_URL = "https://apps.microsoft.com/detail/9pmmsr1cgpwg";
  static final String HEVC_EXTENSION_STORE_URL = "ms-windows-store://pdp/?ProductId=9NMZLZ57R3T7";
  static final String HEVC_EXTENSION_WEB_URL = "https://apps.microsoft.com/detail/9nmzlz57r3t7";

  // TODO(linux backend): the Linux backend should detect the distribution (/etc/os-release) and pass its command through
  // HeifBackendStatus.withInstallCommand (Fedora: RPM Fusion's libheif-freeworld, Arch: libheif, openSUSE: libheif1 +
  // libheif-hevc?, ...). These Debian/Ubuntu commands are only the fallback.
  static final String LIBHEIF_COMMAND = "sudo apt install libheif1 libheif-plugin-libde265";
  static final String HEVC_PLUGIN_COMMAND = "sudo apt install libheif-plugin-libde265";

  private HeifRemedies() {
  }

  /** The remedy of an unavailable status, or {@code null} if {@code status} is available. */
  public static @Nullable HeifRemedy forStatus(@NotNull HeifBackendStatus status) {
    Reason reason = status.reason();
    if (reason == null) return null;
    String installUrl = isAllowedUrl(status.installUrl()) ? status.installUrl() : null;
    String command = isAllowedCommand(status.installCommand()) ? status.installCommand() : null;
    return build(reason, installUrl, command);
  }

  /** The remedy of {@code reason} with the default install page and command only. */
  public static @NotNull HeifRemedy forReason(@NotNull Reason reason) {
    return build(reason, null, null);
  }

  /**
   * @param installUrl the backend's install page (replaces the default one), or {@code null}
   * @param command    the backend's install command (replaces the default one), or {@code null}
   */
  private static HeifRemedy build(Reason reason, @Nullable String installUrl, @Nullable String command) {
    List<Action> actions = new ArrayList<>();
    switch (reason) {
      case WINDOWS_HEIF_EXTENSION_MISSING:
        return storeRemedy(reason, installUrl, command, HEIF_EXTENSION_STORE_URL, HEIF_EXTENSION_WEB_URL);
      case WINDOWS_HEVC_EXTENSION_MISSING:
        return storeRemedy(reason, installUrl, command, HEVC_EXTENSION_STORE_URL, HEVC_EXTENSION_WEB_URL);
      case LINUX_LIBHEIF_MISSING:
      case LINUX_HEVC_PLUGIN_MISSING: {
        String shown = command != null ? command : reason == Reason.LINUX_LIBHEIF_MISSING ? LIBHEIF_COMMAND : HEVC_PLUGIN_COMMAND;
        actions.add(Action.copyCommand(shown));
        actions.add(Action.checkAgain());
        if (installUrl != null) actions.add(Action.openUrl(installPageKey(installUrl), installUrl));
        actions.add(Action.learnMore(REQUIREMENTS_URL));
        return new HeifRemedy(reason, shown, actions);
      }
      case ERROR:
        // Unexpected (on macOS practically impossible: ImageIO.framework is part of the system).
        actions.add(Action.checkAgain());
        actions.add(Action.openUrl("remedy.action.report", ISSUES_URL));
        return new HeifRemedy(reason, command, actions);
      case UNSUPPORTED_OS:
      case NOT_IMPLEMENTED:
      default:
        if (installUrl != null) actions.add(Action.openUrl(installPageKey(installUrl), installUrl));
        actions.add(Action.learnMore(REQUIREMENTS_URL));
        return new HeifRemedy(reason, command, actions);
    }
  }

  /** Windows: the Store app first, then Check Again, then the Store's web page (for systems without the Store app). */
  private static HeifRemedy storeRemedy(Reason reason, @Nullable String installUrl, @Nullable String command,
                                        String defaultStoreUrl, String webUrl) {
    List<Action> actions = new ArrayList<>();
    String primary = installUrl != null ? installUrl : defaultStoreUrl;
    actions.add(Action.openUrl(installPageKey(primary), primary));
    if (command != null) actions.add(Action.copyCommand(command));
    actions.add(Action.checkAgain());
    if (!webUrl.equalsIgnoreCase(primary)) actions.add(Action.openUrl("remedy.action.open.store.web", webUrl));
    actions.add(Action.learnMore(REQUIREMENTS_URL));
    return new HeifRemedy(reason, command, actions);
  }

  /** Label of an install page: the Store app for {@code ms-windows-store:} links, otherwise a web page. */
  private static String installPageKey(String url) {
    return url.toLowerCase(Locale.ROOT).startsWith("ms-windows-store:") ? "remedy.action.open.store" : "remedy.action.open.install.page";
  }

  /**
   * Whether the UI may open {@code url}: {@code https:} with a host, or a Microsoft Store link
   * ({@code ms-windows-store:}). Everything else ({@code http:}, {@code file:}, other schemes, malformed) is refused.
   */
  public static boolean isAllowedUrl(@Nullable String url) {
    if (url == null || url.trim().isEmpty() || !url.equals(url.trim())) return false;
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      if (scheme == null) return false;
      switch (scheme.toLowerCase(Locale.ROOT)) {
        case "https":
          return uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null;
        case "ms-windows-store":
          return url.length() > "ms-windows-store:".length();
        default:
          return false;
      }
    }
    catch (URISyntaxException e) {
      return false;
    }
  }

  /** Whether {@code command} can be shown and copied: one line of printable text. */
  public static boolean isAllowedCommand(@Nullable String command) {
    if (command == null || command.trim().isEmpty() || command.length() > 500) return false;
    for (int i = 0; i < command.length(); i++) {
      if (Character.isISOControl(command.charAt(i))) return false;
    }
    return true;
  }
}
