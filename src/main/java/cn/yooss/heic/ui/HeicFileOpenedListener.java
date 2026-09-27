package cn.yooss.heic.ui;

import cn.yooss.heic.HeicImageReaderSpi;
import cn.yooss.heic.backend.HeifBackendStatus;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;

/**
 * Shows the banner of {@link HeicDecoderNotificationProvider} when a HEIC file is opened while the decoder is missing;
 * the platform collects banners by itself only for text editors.
 */
public final class HeicFileOpenedListener implements FileEditorManagerListener {
  @Override
  public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    if (!HeicImageReaderSpi.isHeicExtension(file.getExtension())) return;
    HeifBackendStatus status = DecoderStatus.cached();
    if (status == null) {
      DecoderStatus.status(); // probes on a pooled thread; a missing decoder updates the banners
    }
    else if (!status.isAvailable() && !HeicViews.isBannerHidden(status.reason())) {
      EditorNotifications.getInstance(source.getProject()).updateNotifications(file);
    }
  }
}
