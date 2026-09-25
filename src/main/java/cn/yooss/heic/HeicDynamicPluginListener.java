package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.thumbnail.HeicThumbnails;
import cn.yooss.heic.ui.DecoderUi;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Install, enable, update and uninstall without restart: register the reader after this plugin has been loaded and
 * deregister it before it is unloaded, so that nothing in the global ImageIO registry pins the plugin class loader.
 * Before unloading, the thumbnail icon machinery is shut down as well (executor, caches, queued runnables).
 */
public final class HeicDynamicPluginListener implements DynamicPluginListener {
  private static final Logger LOG = Logger.getInstance(HeicDynamicPluginListener.class);

  @Override
  public void pluginLoaded(@NotNull IdeaPluginDescriptor pluginDescriptor) {
    if (!isThisPlugin(pluginDescriptor)) return;
    try {
      if (HeicSupport.register()) {
        HeicFileTypeMappingRepair.schedule();
        DecoderUi.checkInBackground(true); // just installed or updated: tell right away if the decoder is missing
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
      // Before the reader goes away: stops thumbnail decoding and releases the executor, caches and queued runnables.
      HeicThumbnails.shutDown();
    }
    finally {
      try {
        DecoderUi.shutDown(); // expires the balloons (their actions are plugin classes), no more banners
      }
      finally {
        try {
          HeicSupport.shutDown();
        }
        finally {
          HeifBackends.shutDown(); // lets the backend release native resources
        }
      }
    }
  }

  private static boolean isThisPlugin(IdeaPluginDescriptor descriptor) {
    return HeicSupport.PLUGIN_ID.equals(descriptor.getPluginId().getIdString());
  }
}
