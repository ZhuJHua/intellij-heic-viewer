package cn.yooss.heic;

import cn.yooss.heic.ui.DecoderUi;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Normal IDE start: {@code appFrameCreated} runs before any project (and therefore any HEIC editor tab) is reopened.
 */
public final class HeicAppLifecycleListener implements AppLifecycleListener {
  private static final Logger LOG = Logger.getInstance(HeicAppLifecycleListener.class);

  @Override
  public void appFrameCreated(@NotNull List<String> commandLineArgs) {
    try {
      if (HeicSupport.register()) {
        HeicFileTypeMappingRepair.schedule();
        DecoderUi.start(false); // probes off the EDT; the user is told where a HEIC image fails to load
      }
    }
    catch (RuntimeException | LinkageError e) {
      LOG.warn("Cannot enable HEIC Viewer", e);
    }
  }
}
