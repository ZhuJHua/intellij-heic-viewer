package cn.yooss.heic.backend;

import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifRemedy.Action;
import cn.yooss.heic.win.WindowsCodecs;
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
 * <table>
 *   <caption>Remedies (the banner shows the first two actions and "More" when there are more than three)</caption>
 *   <tr><th>Reason</th><th>Actions</th></tr>
 *   <tr><td>{@code WINDOWS_HEIF_EXTENSION_MISSING}</td><td>Open Microsoft Store (HEIF Image Extension,
 *   {@value WindowsCodecs#HEIF_PRODUCT_ID}), Check Again, Copy Command (winget), Open Store Page in Browser, Learn More</td></tr>
 *   <tr><td>{@code WINDOWS_HEVC_EXTENSION_MISSING}</td><td>Open Microsoft Store (HEVC Video Extensions,
 *   {@value WindowsCodecs#HEVC_PRODUCT_ID}), Check Again, Open Store Page in Browser, Learn More</td></tr>
 *   <tr><td>{@code LINUX_LIBHEIF_MISSING}, {@code LINUX_HEVC_PLUGIN_MISSING}</td><td>Copy Command (the command of the
 *   detected distribution, also shown in the banner), Check Again, Learn More ({@link #LINUX_HELP_URL}); without a
 *   command (Flatpak, unknown distribution): Installation Instructions ({@link #LINUX_HELP_URL}), Check Again</td></tr>
 *   <tr><td>{@code ERROR}</td><td>Check Again, Report a Problem</td></tr>
 *   <tr><td>{@code UNSUPPORTED_OS}</td><td>Learn More</td></tr>
 * </table>
 * The Microsoft Store data (product ids, pages) is {@link WindowsCodecs}'; the Linux install commands come from the
 * Linux backend ({@code cn.yooss.heic.linux.LibheifRemedy}, per distribution, from {@code /etc/os-release}).
 * <p>
 * A backend knows the details of the running system better than these defaults: {@link HeifBackendStatus#installUrl()}
 * replaces the default install page (the Store app page on Windows, the README section on Linux) and
 * {@link HeifBackendStatus#installCommand()} (e.g. the package command of the detected Linux distribution) is offered to
 * copy. Values that are not {@linkplain #isAllowedUrl allowed URLs} or {@linkplain #isAllowedCommand single-line
 * commands} are ignored, so a backend can never make the IDE open another URL scheme. {@code HeifRemediesTest} checks
 * every remedy (texts in both bundles, URL schemes, commands).
 */
public final class HeifRemedies {
  /** The project page; the README's "Requirements" section explains what each OS needs. */
  public static final String PROJECT_URL = "https://github.com/ZhuJHua/intellij-heic-viewer";
  static final String REQUIREMENTS_URL = PROJECT_URL + "#requirements";
  /** README section about the Microsoft Store extensions Windows needs. */
  public static final String WINDOWS_HELP_URL = PROJECT_URL + "#windows-heif-and-hevc-extensions";
  /** README section with the install commands of every distribution, Flatpak and custom libheif locations. */
  public static final String LINUX_HELP_URL = PROJECT_URL + "#linux-libheif";
  static final String ISSUES_URL = PROJECT_URL + "/issues";

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

  /** The remedy of {@code reason} with the default install page and no command. */
  public static @NotNull HeifRemedy forReason(@NotNull Reason reason) {
    return build(reason, null, null);
  }

  /**
   * @param installUrl the backend's install page (replaces the default one), or {@code null}
   * @param command    the backend's install command, or {@code null}
   */
  private static HeifRemedy build(Reason reason, @Nullable String installUrl, @Nullable String command) {
    List<Action> actions = new ArrayList<>();
    switch (reason) {
      case WINDOWS_HEIF_EXTENSION_MISSING:
        return storeRemedy(reason, installUrl, command, WindowsCodecs.HEIF_STORE_APP_URL, WindowsCodecs.HEIF_STORE_URL);
      case WINDOWS_HEVC_EXTENSION_MISSING:
        return storeRemedy(reason, installUrl, command, WindowsCodecs.HEVC_STORE_APP_URL, WindowsCodecs.HEVC_STORE_URL);
      case LINUX_LIBHEIF_MISSING:
      case LINUX_HEVC_PLUGIN_MISSING: {
        String help = installUrl != null ? installUrl : LINUX_HELP_URL;
        if (command != null) {
          // The package command of the detected distribution: the banner shows it, Copy Command comes first.
          actions.add(Action.copyCommand(command));
          actions.add(Action.checkAgain());
          actions.add(Action.learnMore(help));
        }
        else {
          // No command for this system (Flatpak, an unknown distribution): the README explains the options.
          actions.add(Action.openUrl(installPageKey(help), help));
          actions.add(Action.checkAgain());
        }
        return new HeifRemedy(reason, command, actions);
      }
      case ERROR:
        // Unexpected (on macOS practically impossible: ImageIO.framework is part of the system).
        actions.add(Action.checkAgain());
        actions.add(Action.openUrl("remedy.action.report", ISSUES_URL));
        return new HeifRemedy(reason, command, actions);
      case UNSUPPORTED_OS:
      default:
        if (installUrl != null) actions.add(Action.openUrl(installPageKey(installUrl), installUrl));
        actions.add(Action.learnMore(REQUIREMENTS_URL));
        return new HeifRemedy(reason, command, actions);
    }
  }

  /**
   * Windows: the Store app first, then Check Again (both visible in the banner), then the winget command if the backend
   * has one, the Store's web page (for systems without the Store app, e.g. Windows Server and LTSC) and the README.
   */
  private static HeifRemedy storeRemedy(Reason reason, @Nullable String installUrl, @Nullable String command,
                                        String defaultStoreUrl, String webUrl) {
    List<Action> actions = new ArrayList<>();
    String primary = installUrl != null ? installUrl : defaultStoreUrl;
    actions.add(Action.openUrl(installPageKey(primary), primary));
    actions.add(Action.checkAgain());
    if (command != null) actions.add(Action.copyCommand(command));
    if (!webUrl.equalsIgnoreCase(primary)) actions.add(Action.openUrl("remedy.action.open.store.web", webUrl));
    actions.add(Action.learnMore(WINDOWS_HELP_URL));
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
