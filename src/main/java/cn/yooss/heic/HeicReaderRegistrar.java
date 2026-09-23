package cn.yooss.heic;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the reader in the command-line diff and merge starters ({@code studio diff a.heic b.heic},
 * {@code studio merge ...}, git difftool/mergetool configured to use the IDE) when the IDE is not running yet. Those
 * starters never create an IDE frame, so {@link HeicAppLifecycleListener#appFrameCreated} does not run there. But
 * before the image viewer of a diff or merge window (or any editor) is created for a file, the platform asks every
 * {@code fileEditorProvider} registered for its file type whether it accepts the file: this provider registers the
 * reader and never accepts.
 * <p>
 * It is registered for the Image file type only ({@code fileType="Image"} in the plugin descriptor); in a normal IDE
 * session the reader is already registered and {@link #accept} is a synchronized null test. The platform closes only
 * the editors created by a provider that is removed, and this one never creates any, so installing, updating or
 * removing the plugin does not disturb open editors or diff windows. After {@code beforePluginUnload},
 * {@link HeicSupport#register()} no longer registers anything.
 */
public final class HeicReaderRegistrar implements FileEditorProvider, DumbAware {
  private static final Logger LOG = Logger.getInstance(HeicReaderRegistrar.class);

  @Override
  public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
    try {
      if (!HeicSupport.isRegistered()) HeicSupport.register();
    }
    catch (RuntimeException | LinkageError e) {
      LOG.warn("Cannot enable HEIC Viewer", e);
    }
    return false;
  }

  @Override
  public boolean acceptRequiresReadAction() {
    return false; // touches neither PSI nor VFS
  }

  @Override
  public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
    throw new UnsupportedOperationException("Never used: accept() is always false");
  }

  @Override
  public @NotNull String getEditorTypeId() {
    return "heic-reader-registration";
  }

  @Override
  public @NotNull FileEditorPolicy getPolicy() {
    return FileEditorPolicy.NONE;
  }
}
