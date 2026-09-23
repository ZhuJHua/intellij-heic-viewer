package cn.yooss.heic;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/** User-visible strings ({@code messages/HeicBundle.properties}, localized in {@code HeicBundle_zh_CN.properties}). */
public final class HeicBundle {
  @NonNls
  public static final String BUNDLE = "messages.HeicBundle";
  private static final DynamicBundle INSTANCE = new DynamicBundle(HeicBundle.class, BUNDLE);

  private HeicBundle() {
  }

  public static @NotNull @Nls String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
    return INSTANCE.getMessage(key, params);
  }
}
