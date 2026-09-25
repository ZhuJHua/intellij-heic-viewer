package cn.yooss.heic.ui;

import cn.yooss.heic.HeicImageReaderSpi;
import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffExtension;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.FileContent;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * The diff viewer shows no editor banner: when a diff of HEIC files is opened while the system decoder is unavailable
 * (its images show "Image not loaded"), the "decoder missing" balloon explains why, once per session
 * ({@link DecoderPrompt}). Called on the EDT for every diff viewer; only compares file extensions and reads the cached
 * status (a probe, if needed, runs on a pooled thread).
 */
public final class HeicDiffExtension extends DiffExtension {
  @Override
  public void onViewerCreated(@NotNull FrameDiffTool.DiffViewer viewer, @NotNull DiffContext context,
                              @NotNull DiffRequest request) {
    if (!(request instanceof ContentDiffRequest) || !showsHeicFile((ContentDiffRequest) request)) return;
    Project project = context.getProject();
    DecoderStatus.status().thenAccept(status -> {
      if (!status.isAvailable()) DecoderPrompt.decodeUnavailable(status, project);
    });
  }

  static boolean showsHeicFile(@NotNull ContentDiffRequest request) {
    for (DiffContent content : request.getContents()) {
      if (!(content instanceof FileContent)) continue;
      VirtualFile file = ((FileContent) content).getFile();
      if (HeicImageReaderSpi.isHeicExtension(file.getExtension())) return true;
    }
    return false;
  }
}
