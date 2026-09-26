package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.ui.DecoderUi;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Install, enable, update and uninstall without restart: registers the reader after this plugin has been loaded and
 * deregisters it before it is unloaded, so that nothing in the global ImageIO registry pins the plugin class loader.
 * Last, threads that plugin code started release the plugin class loader ({@link InheritedContexts}, JDK 17-23).
 */
public final class HeicDynamicPluginListener implements DynamicPluginListener {
  private static final Logger LOG = Logger.getInstance(HeicDynamicPluginListener.class);

  @Override
  public void pluginLoaded(@NotNull IdeaPluginDescriptor pluginDescriptor) {
    if (!isThisPlugin(pluginDescriptor)) return;
    try {
      if (HeicSupport.register()) {
        HeicFileTypeMappingRepair.schedule();
        DecoderUi.start(true); // just installed or updated: tell right away if the decoder is missing
      }
    }
    catch (RuntimeException | LinkageError e) {
      LOG.warn("Cannot enable HEIC Viewer", e);
    }
  }

  @Override
  public void beforePluginUnload(@NotNull IdeaPluginDescriptor pluginDescriptor, boolean isUpdate) {
    if (!isThisPlugin(pluginDescriptor)) return;
    try {
      DecoderUi.shutDown(); // expires the balloons (their actions are plugin classes), no more banners
    }
    finally {
      try {
        HeicSupport.shutDown();
      }
      finally {
        try {
          HeifBackends.shutDown(); // lets the backend release native resources
        }
        finally {
          releaseInheritedContexts(); // last: threads started by plugin code (JDK 17-23), e.g. an application pool thread
        }
      }
    }
  }

  private static void releaseInheritedContexts() {
    try {
      List<String> threads = InheritedContexts.release();
      if (!threads.isEmpty()) {
        LOG.info("Released the plugin class loader from the access control context inherited by " + threads.size()
                 + " thread(s): " + threads);
      }
    }
    catch (RuntimeException | LinkageError e) { // e.g. a future Java without java.security.AccessControlContext
      LOG.info("Cannot release the plugin class loader from inherited access control contexts", e);
    }
  }

  private static boolean isThisPlugin(IdeaPluginDescriptor descriptor) {
    return HeicSupport.PLUGIN_ID.equals(descriptor.getPluginId().getIdString());
  }
}
