package cn.yooss.heic;

import com.intellij.openapi.options.advanced.AdvancedSettings;

/**
 * Plugin settings, shown under Settings | Advanced Settings | HEIC Viewer (declared in plugin.xml, texts in
 * {@code messages/HeicBundle.properties}).
 */
public final class HeicSettings {
  /** Integer: pixel budget of a decoded image in megapixels; larger images are downscaled while decoding. */
  public static final String MAX_MEGAPIXELS = "heic.viewer.max.megapixels";
  /** Boolean: show thumbnails as the icons of HEIC files (project view, editor tabs, ...). */
  public static final String PROJECT_VIEW_THUMBNAILS = "heic.viewer.project.view.thumbnails";

  private HeicSettings() {
  }

  /** Current decode limits; falls back to {@link DecodeLimits#DEFAULT} when the setting is unavailable. */
  public static DecodeLimits decodeLimits() {
    return DecodeLimits.ofMegapixels(getInt(MAX_MEGAPIXELS, DecodeLimits.DEFAULT_MAX_MEGAPIXELS));
  }

  /** Whether HEIC files are shown with a thumbnail icon; {@code true} when the setting is unavailable. */
  public static boolean projectViewThumbnails() {
    return getBoolean(PROJECT_VIEW_THUMBNAILS, true);
  }

  /**
   * Reads an integer advanced setting. Never throws: outside a running IDE, while the plugin is being unloaded or if
   * the setting is not registered, returns {@code defaultValue}.
   */
  static int getInt(String id, int defaultValue) {
    try {
      return AdvancedSettings.getInt(id);
    }
    catch (RuntimeException | LinkageError e) {
      return defaultValue;
    }
  }

  /** Boolean counterpart of {@link #getInt(String, int)}. */
  static boolean getBoolean(String id, boolean defaultValue) {
    try {
      return AdvancedSettings.getBoolean(id);
    }
    catch (RuntimeException | LinkageError e) {
      return defaultValue;
    }
  }
}
