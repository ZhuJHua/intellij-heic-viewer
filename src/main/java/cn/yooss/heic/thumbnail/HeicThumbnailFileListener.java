package cn.yooss.heic.thumbnail;

import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** A HEIC file shown with a thumbnail changed on disk: refresh its icon (see {@link HeicThumbnails#filesChanged}). */
public final class HeicThumbnailFileListener implements BulkFileListener {
  @Override
  public void after(@NotNull List<? extends VFileEvent> events) {
    HeicThumbnails.filesChanged(events);
  }
}
