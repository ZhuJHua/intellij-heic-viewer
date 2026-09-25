package cn.yooss.heic.backend;

import cn.yooss.heic.linux.LibheifHeifBackend;
import cn.yooss.heic.mac.MacHeifBackend;
import cn.yooss.heic.win.WicHeifBackend;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Selects the {@link HeifBackend} for the running operating system, once per plugin class loader:
 * <table>
 *   <caption>Backends</caption>
 *   <tr><th>OS</th><th>Backend</th><th>System decoder</th></tr>
 *   <tr><td>macOS</td><td>{@link MacHeifBackend}</td><td>ImageIO.framework</td></tr>
 *   <tr><td>Windows</td><td>{@link WicHeifBackend}</td><td>WIC + "HEIF Image Extensions" + "HEVC Video Extensions"</td></tr>
 *   <tr><td>Linux</td><td>{@link LibheifHeifBackend}</td><td>{@code libheif.so.1} + an HEVC decoder plugin</td></tr>
 *   <tr><td>other</td><td>{@link UnavailableHeifBackend}</td><td>none ({@code UNSUPPORTED_OS})</td></tr>
 * </table>
 * Creating a backend never loads native code, so {@link #current()} is cheap and safe on any thread.
 */
public final class HeifBackends {
  /** Operating system families with a backend. */
  public enum Os {
    MAC, WINDOWS, LINUX, OTHER;

    /** The running OS, from {@code os.name}. */
    public static @NotNull Os current() {
      return of(System.getProperty("os.name", ""));
    }

    static @NotNull Os of(@NotNull String osName) {
      String name = osName.toLowerCase(Locale.ROOT);
      if (name.startsWith("mac") || name.startsWith("darwin")) return MAC;
      if (name.startsWith("windows")) return WINDOWS;
      if (name.startsWith("linux")) return LINUX;
      return OTHER;
    }
  }

  private static final Object LOCK = new Object();
  private static volatile HeifBackend current;

  private HeifBackends() {
  }

  /** The backend for this OS (created on the first call, without probing it). */
  public static @NotNull HeifBackend current() {
    HeifBackend result = current;
    if (result != null) return result;
    synchronized (LOCK) {
      if (current == null) current = create(Os.current());
      return current;
    }
  }

  /** A new backend instance for {@code os} (tests; the plugin uses {@link #current()}). */
  public static @NotNull HeifBackend create(@NotNull Os os) {
    switch (os) {
      case MAC:
        return new MacHeifBackend();
      case WINDOWS:
        return new WicHeifBackend();
      case LINUX:
        return new LibheifHeifBackend();
      default:
        return UnavailableHeifBackend.unsupportedOs(System.getProperty("os.name", "?"), System.getProperty("os.arch", "?"));
    }
  }

  /**
   * Before the plugin is unloaded: lets the current backend release its native resources. A later {@link #current()}
   * (which should not happen) would create a new instance.
   */
  public static void shutDown() {
    HeifBackend backend;
    synchronized (LOCK) {
      backend = current;
      current = null;
    }
    if (backend != null) backend.dispose();
  }
}
