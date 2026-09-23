package cn.yooss.heic.thumbnail;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Shows a small thumbnail as the icon of local {@code .heic/.heif/.hif/.heics} files (project view, editor tabs,
 * navigation bar, Recent Files ...), controlled by the advanced setting
 * {@code heic.viewer.project.view.thumbnails}.
 * <p>
 * Returns {@code null} (= the Image file type icon) until the thumbnail has been decoded in the background, see
 * {@link HeicThumbnails}. Never decodes on the calling thread, which may be the EDT.
 */
public final class HeicThumbnailIconProvider implements FileIconProvider, DumbAware {
  @Override
  public @Nullable Icon getIcon(@NotNull VirtualFile file, int flags, @Nullable Project project) {
    return HeicThumbnails.getIcon(file);
  }
}
