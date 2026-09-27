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
 * Keeps the HEIC extensions mapped to the Image file type. A {@code <removed_mapping ext="heic" type="Image"/>} entry,
 * which the IDE writes to {@code filetypes.xml} when it saves its file-type settings while the plugin is unloaded,
 * removes the plugin's mapping on the next start. Each extension that resolves to no file type
 * ({@link UnknownFileType}) is associated with the Image file type again; an extension the user mapped to another file
 * type is left alone.
 */
final class HeicFileTypeMappingRepair {
  private static final Logger LOG = Logger.getInstance(HeicFileTypeMappingRepair.class);

  private HeicFileTypeMappingRepair() {
  }

  /**
   * Checks the mappings (read-only, any thread) and, only if an extension has lost its mapping, queues the repair on
   * the EDT for when no modal dialog is open. Posts nothing to the EDT otherwise: on Java 17 an EDT event created by
   * plugin code can keep the plugin class loader alive ({@link InheritedContexts}).
   */
  static void schedule() {
    Application application = ApplicationManager.getApplication();
    if (application == null || application.isDisposed()) return;
    List<String> unmapped = unmappedExtensions();
    if (unmapped.isEmpty()) return;
    LOG.info("File type mapping of " + unmapped + " is missing; re-associating them with the Image file type");
    application.invokeLater(HeicFileTypeMappingRepair::runNow, ModalityState.nonModal(), expired(application));
  }

  /** Our extensions that currently resolve to no file type at all. Read-only. */
  static List<String> unmappedExtensions() {
    FileTypeManager fileTypeManager = FileTypeManager.getInstance();
    List<String> unmapped = new ArrayList<>();
    for (String extension : HeicImageReaderSpi.SUFFIXES) {
      if (fileTypeManager.getFileTypeByExtension(extension) instanceof UnknownFileType) {
        unmapped.add(extension);
      }
    }
    return unmapped;
  }

  /**
   * True once the application is disposed or the reader has been deregistered, so that a repair still queued when the
   * plugin is unloaded is dropped instead of pinning the plugin class loader.
   */
  static Condition<Object> expired(Application application) {
    return ignored -> application.isDisposed() || !HeicSupport.isRegistered();
  }

  private static void runNow() {
    if (!HeicSupport.isRegistered()) return; // the plugin was unloaded in the meantime
    List<String> unmapped = unmappedExtensions(); // again: the user may have mapped them in the meantime
    if (unmapped.isEmpty()) return;

    FileTypeManager fileTypeManager = FileTypeManager.getInstance();
    FileType imageFileType = ImageFileTypeManager.getInstance().getImageFileType();
    WriteAction.run(() -> {
      for (String extension : unmapped) {
        fileTypeManager.associateExtension(imageFileType, extension);
      }
    });
    LOG.info("Re-associated " + unmapped + " with the " + imageFileType.getName()
             + " file type (a removed_mapping in filetypes.xml had unmapped them)");
  }
}
