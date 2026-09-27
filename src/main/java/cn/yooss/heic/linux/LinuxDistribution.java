package cn.yooss.heic.linux;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Linux distribution the IDE runs on, from {@code os-release(5)} ({@code /etc/os-release}, falling back to
 * {@code /usr/lib/os-release}), plus whether the IDE runs in a Flatpak sandbox. Used to tell the user the exact
 * command that installs libheif ({@link LibheifRemedy}). Pure Java; never throws (an unreadable file gives an unknown
 * distribution).
 * <p>
 * Immutable, with hand-written {@code equals}/{@code hashCode}/{@code toString} (see {@code HeifBackendStatus}).
 */
public final class LinuxDistribution {
  private static final String[] OS_RELEASE_FILES = {"/etc/os-release", "/usr/lib/os-release"};
  private static final Pattern VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?");

  private final Map<String, String> fields;
  private final boolean flatpak;

  private LinuxDistribution(Map<String, String> fields, boolean flatpak) {
    this.fields = Collections.unmodifiableMap(fields);
    this.flatpak = flatpak;
  }

  /** The running system (reads the files each time; cheap). */
  public static @NotNull LinuxDistribution current() {
    String content = "";
    for (String file : OS_RELEASE_FILES) {
      try {
        content = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        break;
      }
      catch (IOException | RuntimeException e) {
        // try the next file
      }
    }
    boolean flatpak = exists("/.flatpak-info") || System.getenv("FLATPAK_ID") != null;
    return parse(content, flatpak);
  }

  private static boolean exists(String path) {
    try {
      return Files.exists(Paths.get(path));
    }
    catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Parses the content of an {@code os-release} file: {@code KEY=value} lines, values optionally in double or single
   * quotes with the shell escapes {@code \" \\ \$ \`} (in double quotes), {@code #} comments.
   */
  public static @NotNull LinuxDistribution parse(@NotNull String osRelease, boolean flatpak) {
    Map<String, String> fields = new HashMap<>();
    for (String rawLine : osRelease.split("\\r?\\n")) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int eq = line.indexOf('=');
      if (eq <= 0) continue;
      String key = line.substring(0, eq).trim();
      if (!key.matches("[A-Za-z0-9_]+")) continue;
      fields.put(key, unquote(line.substring(eq + 1).trim()));
    }
    return new LinuxDistribution(fields, flatpak);
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
      return value.substring(1, value.length() - 1);
    }
    boolean quoted = value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"';
    String body = quoted ? value.substring(1, value.length() - 1) : value;
    StringBuilder result = new StringBuilder(body.length());
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '\\' && i + 1 < body.length() && "\"\\$`".indexOf(body.charAt(i + 1)) >= 0) {
        result.append(body.charAt(++i));
      }
      else if (!quoted && (c == '"' || c == '\'')) {
        // stray quotes of an unquoted value
      }
      else {
        result.append(c);
      }
    }
    return result.toString();
  }

  /** A field such as {@code ID} or {@code VERSION_ID}, or {@code null}. */
  public @Nullable String field(@NotNull String key) {
    return fields.get(key);
  }

  /** {@code ID} in lower case, e.g. {@code ubuntu}, {@code fedora}, {@code opensuse-tumbleweed}; {@code ""} if unknown. */
  public @NotNull String id() {
    String id = fields.get("ID");
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }

  /** {@code ID_LIKE}, split at spaces, lower case, e.g. {@code [ubuntu, debian]} for Linux Mint. */
  public @NotNull List<String> idLike() {
    String idLike = fields.get("ID_LIKE");
    List<String> result = new ArrayList<>();
    if (idLike == null) return result;
    for (String part : idLike.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
      if (!part.isEmpty()) result.add(part);
    }
    return result;
  }

  /** Whether {@code ID} or one of {@code ID_LIKE} is {@code family}. */
  public boolean isLike(@NotNull String family) {
    return id().equals(family) || idLike().contains(family);
  }

  /** {@code VERSION_ID}, e.g. {@code 24.04}, {@code 41}, {@code 15.6}; {@code ""} for rolling releases. */
  public @NotNull String versionId() {
    String version = fields.get("VERSION_ID");
    return version == null ? "" : version.trim();
  }

  /** {@code PRETTY_NAME}, else {@code NAME}, else {@code ID}, for messages. */
  public @NotNull String prettyName() {
    for (String key : new String[]{"PRETTY_NAME", "NAME", "ID"}) {
      String value = fields.get(key);
      if (value != null && !value.trim().isEmpty()) return value.trim();
    }
    return "unknown Linux distribution";
  }

  /** Whether the IDE runs as a Flatpak (it then only sees the libraries of its Flatpak runtime). */
  public boolean isFlatpak() {
    return flatpak;
  }

  /**
   * The major and minor number of {@code VERSION_ID} (e.g. 24.04 as {@code 2404}, 12 as {@code 1200}, 15.6 as
   * {@code 1506}), or -1 if there is none.
   */
  public int versionNumber() {
    String version = versionId();
    Matcher m = VERSION.matcher(version);
    if (!m.find()) return -1;
    try {
      long major = Long.parseLong(m.group(1));
      long minor = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
      return (int) Math.min(Integer.MAX_VALUE, major * 100 + Math.min(minor, 99));
    }
    catch (NumberFormatException e) {
      return -1;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof LinuxDistribution)) return false;
    LinuxDistribution other = (LinuxDistribution) o;
    return flatpak == other.flatpak && fields.equals(other.fields);
  }

  @Override
  public int hashCode() {
    return 31 * fields.hashCode() + (flatpak ? 1 : 0);
  }

  @Override
  public String toString() {
    return prettyName() + " (ID=" + id() + ", ID_LIKE=" + String.join(" ", idLike()) + ", VERSION_ID=" + versionId()
           + (flatpak ? ", Flatpak" : "") + ")";
  }
}
