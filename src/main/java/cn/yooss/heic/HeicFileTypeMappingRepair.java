package cn.yooss.heic;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.util.Condition;
import org.intellij.images.fileTypes.ImageFileTypeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Workaround for IJPL-39443.
 * <p>
 * When the IDE saves its file-type settings while this plugin is unloaded (after an uninstall, a disable, or an
 * update whose unload did not complete), {@code filetypes.xml} records {@code <removed_mapping ext="heic"
 * type="Image"/>}. On the next start that entry removes the mapping this plugin contributes, so {@code .heic} files
 * become "unknown" although the plugin is installed and its reader is registered.
 * <p>
 * This repair re-associates each of our extensions with the Image file type, but only if the extension currently
 * resolves to no file type at all ({@link UnknownFileType}); an extension the user mapped to another file type is left
 * alone. Only public API is used, so an explicit removal of the mapping (indistinguishable from the stale entry
 * through public API) is reverted as well; to stop HEIC files from opening as images, disable the plugin.
 * Side effect: the repaired mapping is stored explicitly in {@code filetypes.xml} and survives an uninstall.
 */
final class HeicFileTypeMappingRepair {
  private static final Logger LOG = Logger.getInstance(HeicFileTypeMappingRepair.class);

  private HeicFileTypeMappingRepair() {
  }

  /**
   * Schedules the repair on the EDT (a write action is required to change file type associations). It runs when no
   * modal dialog is open; until then it stays queued, e.g. while Settings | Plugins is open after an installation.
   */
  static void schedule() {
    Application application = ApplicationManager.getApplication();
    if (application == null || application.isDisposed()) return;
    application.invokeLater(HeicFileTypeMappingRepair::runNow, ModalityState.nonModal(), expired(application));
  }

  /**
   * True once the application is disposed or the reader has been deregistered. {@code beforePluginUnload}
   * deregisters the reader before {@code DynamicPlugins} purges expired EDT runnables, so a repair still queued
   * behind a modal dialog is dropped instead of pinning the plugin class loader (which would make an uninstall or
   * update in the same Settings session require a restart).
   */
  static Condition<Object> expired(Application application) {
    return ignored -> application.isDisposed() || !HeicSupport.isRegistered();
  }

  private static void runNow() {
    if (!HeicSupport.isRegistered()) return; // the plugin was unloaded in the meantime, or the platform is unsupported
    FileTypeManager fileTypeManager = FileTypeManager.getInstance();
    List<String> unmapped = new ArrayList<>();
    for (String extension : HeicImageReaderSpi.SUFFIXES) {
      if (fileTypeManager.getFileTypeByExtension(extension) instanceof UnknownFileType) {
        unmapped.add(extension);
      }
    }
    if (unmapped.isEmpty()) return;

    FileType imageFileType = ImageFileTypeManager.getInstance().getImageFileType();
    WriteAction.run(() -> {
      for (String extension : unmapped) {
        fileTypeManager.associateExtension(imageFileType, extension);
      }
    });
    LOG.info("Re-associated " + unmapped + " with the " + imageFileType.getName()
             + " file type (a stale removed_mapping in filetypes.xml had disabled them, IJPL-39443)");
  }
}
