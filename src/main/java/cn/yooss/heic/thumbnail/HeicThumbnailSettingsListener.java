package cn.yooss.heic.thumbnail;

import cn.yooss.heic.HeicSettings;
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener;
import org.jetbrains.annotations.NotNull;

/** Applies a change of the thumbnails setting to the icons that are already shown. */
public final class HeicThumbnailSettingsListener implements AdvancedSettingsChangeListener {
  @Override
  public void advancedSettingChanged(@NotNull String id, @NotNull Object oldValue, @NotNull Object newValue) {
    if (HeicSettings.PROJECT_VIEW_THUMBNAILS.equals(id)) {
      HeicThumbnails.settingChanged();
    }
  }
}
