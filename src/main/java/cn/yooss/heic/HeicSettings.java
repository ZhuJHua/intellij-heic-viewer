package cn.yooss.heic;

import com.intellij.openapi.options.advanced.AdvancedSettings;

/**
 * Plugin settings, shown under Settings | Advanced Settings | HEIC Viewer (declared in plugin.xml, texts in
 * {@code messages/HeicBundle.properties}).
 */
public final class HeicSettings {
  /** Boolean: show thumbnails as the icons of HEIC files (project view, editor tabs, ...). */
  public static final String PROJECT_VIEW_THUMBNAILS = "heic.viewer.project.view.thumbnails";
  /**
   * String, registered on Linux only: the {@code libheif.so.1} (or the directory that contains it) to use instead of
   * the system's; empty for the system's. Read by every availability probe (IDE start, "Check Again").
   */
  public static final String LIBHEIF_PATH = "heic.viewer.libheif.path";

  private HeicSettings() {
  }

  /** Whether HEIC files are shown with a thumbnail icon; {@code true} when the setting is unavailable. */
  public static boolean projectViewThumbnails() {
    return getBoolean(PROJECT_VIEW_THUMBNAILS, true);
  }

  /** The configured libheif location (Linux), trimmed; empty for the system's libheif or when the setting is unavailable. */
  public static String libheifPath() {
    return getString(LIBHEIF_PATH, "").trim();
  }

  /**
   * Reads a boolean advanced setting. Never throws: outside a running IDE, while the plugin is being unloaded or if
   * the setting is not registered, returns {@code defaultValue}.
   */
  static boolean getBoolean(String id, boolean defaultValue) {
    try {
      return AdvancedSettings.getBoolean(id);
    }
    catch (RuntimeException | LinkageError e) {
      return defaultValue;
    }
  }

  /** String counterpart of {@link #getBoolean(String, boolean)}. */
  static String getString(String id, String defaultValue) {
    try {
      String value = AdvancedSettings.getString(id);
      return value != null ? value : defaultValue;
    }
    catch (RuntimeException | LinkageError e) {
      return defaultValue;
    }
  }
}
