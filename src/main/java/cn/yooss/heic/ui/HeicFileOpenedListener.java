package cn.yooss.heic.ui;

import cn.yooss.heic.HeicImageReaderSpi;
import cn.yooss.heic.backend.HeifBackendStatus;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;

/**
 * Makes the banner of {@link HeicDecoderNotificationProvider} appear when a HEIC file is opened. The platform collects
 * editor notifications by itself only for text editors (they schedule it when they are created); for other editors,
 * such as the image editor, only when something asks for an update (IntelliJ 2024.1 to 2026.2). Does nothing while the
 * decoder is available: an EDT-free volatile read and an extension compare per opened file. Registered in plugin.xml
 * ({@code projectListeners}).
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
