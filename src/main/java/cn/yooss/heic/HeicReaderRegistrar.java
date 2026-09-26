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
 * Registers the reader in the command-line diff and merge starters ({@code studio diff a.heic b.heic}), which never
 * create an IDE frame, so {@link HeicAppLifecycleListener#appFrameCreated} does not run there. The platform asks this
 * provider (registered for the Image file type) before it creates an image viewer for a file: it registers the reader
 * and never accepts, so it creates no editors. After {@code beforePluginUnload}, {@link HeicSupport#register()}
 * registers nothing.
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
