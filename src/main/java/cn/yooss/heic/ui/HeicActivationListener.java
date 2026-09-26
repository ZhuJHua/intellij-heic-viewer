package cn.yooss.heic.ui;

import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

/**
 * Re-checks the system decoder when the IDE is activated after the user clicked a remedy action, so HEIC images load
 * once the missing component is installed ({@link DecoderStatus#applicationActivated()}).
 */
public final class HeicActivationListener implements ApplicationActivationListener {
  @Override
  public void applicationActivated(@NotNull IdeFrame ideFrame) {
    DecoderStatus.applicationActivated();
  }
}
