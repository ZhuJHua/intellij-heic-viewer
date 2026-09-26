package cn.yooss.heic.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Result of a {@link HeifBackend}'s availability probe: {@linkplain #isAvailable() available}, or unavailable with a
 * machine-readable {@link Reason} and a technical {@link #detail() detail} (English, for idea.log). A status of an
 * installable component can carry an {@link #installUrl()} (store page, download or documentation page) and an
 * {@link #installCommand()} (a shell command such as {@code sudo apt install ...}), which the install prompt offers.
 * <p>
 * Immutable, with hand-written {@code equals}/{@code hashCode}/{@code toString}: those of a record are bootstrapped
 * through {@code java.lang.runtime.ObjectMethods}, whose caches pin the plugin class loader on Java 17.
 */
public final class HeifBackendStatus {
  /** Why a backend cannot decode. Each constant has the bundle key {@code backend.status.<NAME>} (EN + zh_CN). */
  public enum Reason {
    /** Windows: the "HEIF Image Extension" (Microsoft Store) is not installed, so WIC has no HEIF decoder. */
    WINDOWS_HEIF_EXTENSION_MISSING(true),
    /** Windows: the HEIF container can be read, but the HEVC codec ("HEVC Video Extensions", Microsoft Store) is missing. */
    WINDOWS_HEVC_EXTENSION_MISSING(true),
    /** Linux: {@code libheif.so.1} cannot be loaded. */
    LINUX_LIBHEIF_MISSING(true),
    /** Linux: libheif is present but has no HEVC decoder plugin (e.g. {@code libheif-plugin-libde265}). */
    LINUX_HEVC_PLUGIN_MISSING(true),
    /** This operating system (or processor architecture) has no supported system decoder. */
    UNSUPPORTED_OS(false),
    /** The probe failed unexpectedly (see {@link #detail()}). */
    ERROR(false);

    private final boolean userInstallable;

    Reason(boolean userInstallable) {
      this.userInstallable = userInstallable;
    }

    /** Whether the user can fix this by installing a system component, i.e. whether the install prompt is shown. */
    public boolean isUserInstallable() {
      return userInstallable;
    }

    /** Resource bundle key of the user-visible explanation. */
    public @NotNull String bundleKey() {
      return "backend.status." + name();
    }
  }

  private final @Nullable Reason reason;
  private final @NotNull String detail;
  private final @Nullable String installUrl;
  private final @Nullable String installCommand;

  private HeifBackendStatus(@Nullable Reason reason, @NotNull String detail, @Nullable String installUrl,
                            @Nullable String installCommand) {
    this.reason = reason;
    this.detail = Objects.requireNonNull(detail, "detail");
    this.installUrl = blankToNull(installUrl);
    this.installCommand = blankToNull(installCommand);
  }

  /** @param detail what was found, e.g. {@code "macOS ImageIO.framework through JNA 5.17.0 (aarch64)"} */
  public static @NotNull HeifBackendStatus available(@NotNull String detail) {
    return new HeifBackendStatus(null, detail, null, null);
  }

  /** @param detail technical explanation for idea.log (English), e.g. the error of {@code dlopen} */
  public static @NotNull HeifBackendStatus unavailable(@NotNull Reason reason, @NotNull String detail) {
    return new HeifBackendStatus(Objects.requireNonNull(reason, "reason"), detail, null, null);
  }

  /** A copy with a page that explains or starts the installation (https:// or e.g. {@code ms-windows-store://}). */
  public @NotNull HeifBackendStatus withInstallUrl(@Nullable String url) {
    return new HeifBackendStatus(reason, detail, url, installCommand);
  }

  /** A copy with a command that installs the missing component (shown and copyable in the prompt). */
  public @NotNull HeifBackendStatus withInstallCommand(@Nullable String command) {
    return new HeifBackendStatus(reason, detail, installUrl, command);
  }

  public boolean isAvailable() {
    return reason == null;
  }

  /** {@code null} if and only if the backend {@linkplain #isAvailable() is available}. */
  public @Nullable Reason reason() {
    return reason;
  }

  public @NotNull String detail() {
    return detail;
  }

  public @Nullable String installUrl() {
    return installUrl;
  }

  public @Nullable String installCommand() {
    return installCommand;
  }

  /** Whether the install prompt should be shown for this status. */
  public boolean isUserInstallable() {
    return reason != null && reason.isUserInstallable();
  }

  private static @Nullable String blankToNull(@Nullable String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof HeifBackendStatus)) return false;
    HeifBackendStatus other = (HeifBackendStatus) o;
    return reason == other.reason && detail.equals(other.detail) && Objects.equals(installUrl, other.installUrl)
           && Objects.equals(installCommand, other.installCommand);
  }

  @Override
  public int hashCode() {
    int result = Objects.hashCode(reason);
    result = 31 * result + detail.hashCode();
    result = 31 * result + Objects.hashCode(installUrl);
    result = 31 * result + Objects.hashCode(installCommand);
    return result;
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder(reason == null ? "AVAILABLE" : "UNAVAILABLE(" + reason + ")");
    text.append(": ").append(detail);
    if (installUrl != null) text.append(" [install: ").append(installUrl).append(']');
    if (installCommand != null) text.append(" [command: ").append(installCommand).append(']');
    return text.toString();
  }
}
