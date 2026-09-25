package cn.yooss.heic.ui;

import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

/**
 * Re-checks the system decoder when the IDE is activated again after the user clicked a remedy action (opened the
 * Microsoft Store, copied an install command, ...), so HEIC images load as soon as the user comes back from installing
 * the component. A volatile read unless such an action was clicked in the last 30 minutes; debounced
 * ({@link DecoderStatus#applicationActivated()}). Registered in plugin.xml ({@code applicationListeners}).
 */
public final class HeicActivationListener implements ApplicationActivationListener {
  @Override
  public void applicationActivated(@NotNull IdeFrame ideFrame) {
    DecoderStatus.applicationActivated();
  }
}
