package cn.yooss.heic.ui;

import cn.yooss.heic.Downscales;
import cn.yooss.heic.HeicBundle;
import cn.yooss.heic.HeicImageReaderSpi;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import org.intellij.images.editor.ImageFileEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * The banner above a HEIC image that the heap safety valve ({@link cn.yooss.heic.HeapValve}) decoded smaller than it
 * is: "Shown at 8752x8752 instead of 16384x16384 to protect IDE memory. Increase the IDE heap (Help | Change Memory
 * Settings) to see it at full size.", with a link to the IDE's Change Memory Settings dialog (the platform action
 * {@value #MEMORY_SETTINGS_ACTION_ID}, in the Help menu of IntelliJ 2024.1 to 2026.2 and Android Studio; no link if an
 * IDE does not have it). The diff shows no banner; the reader logs the reduced decode instead.
 * <p>
 * The banner describes the image the editor shows: the reader tags a reduced image with its full size
 * ({@link Downscales#fromImage}), so a later decode of the same file elsewhere (a diff) cannot change what the banner
 * says, and an image the IDE keeps across a reload of the plugin still gets its banner. The IDE hands the decoded image
 * to the editor only after the reader returned, when the reader has already asked for the banners to be updated
 * ({@link HeicViews#downscalesChanged()}): until then the record of the file's content ({@link Downscales}, length and
 * CRC-32, compared once per modification stamp) stands in.
 * <p>
 * {@link #collectNotificationData} runs on a background thread under a read action: an extension compare, and the
 * content comparison only while an image of the file's length was decoded smaller in this session. The function it
 * returns reads the editor's image on the EDT (a field read). Closing the banner hides it for that file until the IDE
 * restarts.
 */
public final class HeicDownscaleNotificationProvider implements EditorNotificationProvider, DumbAware {
  /** The platform's "Change Memory Settings" action (Help menu), {@code com.intellij.diagnostic.ShowMemoryDialogAction}. */
  static final String MEMORY_SETTINGS_ACTION_ID = "performancePlugin.ShowMemoryDialogAction";

  /** Content identity ({@link Downscales#key}) per file, with the modification stamp it was computed for. */
  private static final Map<VirtualFile, Object[]> contentKeys = new WeakHashMap<>();

  @Override
  public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(@NotNull Project project,
                                                                                            @NotNull VirtualFile file) {
    if (!HeicImageReaderSpi.isHeicExtension(file.getExtension()) || !file.isValid() || file.isDirectory()) return null;
    String path = file.getPath();
    if (HeicViews.isDownscaleBannerHidden(path)) return null;
    Downscales.Entry recorded = !Downscales.isEmpty() && Downscales.hasLength(file.getLength())
                                ? Downscales.find(contentKey(file)) : null;
    // Applied later on the EDT: not after the plugin's shutdown, and not for a banner closed meanwhile.
    return editor -> {
      if (!(editor instanceof ImageFileEditor) || HeicViews.isDownscaleBannerHidden(path)) return null;
      Downscales.Entry shown = shown((ImageFileEditor) editor, recorded);
      return shown != null ? createPanel(editor, shown, path) : null;
    };
  }

  /**
   * What the editor shows: the reduction its image is tagged with, none for an untagged image, or {@code recorded}
   * while the editor has no image yet.
   */
  static @Nullable Downscales.Entry shown(@NotNull ImageFileEditor editor, @Nullable Downscales.Entry recorded) {
    BufferedImage image;
    try {
      image = editor.getImageEditor().getDocument().getValue();
    }
    catch (RuntimeException | LinkageError e) {
      return recorded;
    }
    return image != null ? Downscales.fromImage(image) : recorded;
  }

  /** The content identity of {@code file}, computed once per modification stamp; empty if it cannot be read. */
  private static @NotNull String contentKey(@NotNull VirtualFile file) {
    long stamp = file.getModificationStamp();
    synchronized (contentKeys) {
      Object[] cached = contentKeys.get(file);
      if (cached != null && (Long) cached[0] == stamp) return (String) cached[1];
    }
    String key;
    try {
      byte[] content = file.contentsToByteArray();
      key = Downscales.key(content.length, Downscales.crc(content));
    }
    catch (IOException | RuntimeException e) {
      return "";
    }
    synchronized (contentKeys) {
      contentKeys.put(file, new Object[]{stamp, key});
    }
    return key;
  }

  /** EDT. {@code hideKey}: what closing the banner hides (the file's path). */
  static @NotNull EditorNotificationPanel createPanel(@Nullable FileEditor editor, @NotNull Downscales.Entry entry,
                                                     @NotNull String hideKey) {
    EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
    String text = text(entry);
    panel.setText(text);
    panel.setToolTipText(text); // the label ends with "..." in a narrow editor
    if (entry.isHeapLimited() && ActionManager.getInstance().getAction(MEMORY_SETTINGS_ACTION_ID) != null) {
      panel.createActionLabel(HeicBundle.message("downscale.action.memory.settings"), MEMORY_SETTINGS_ACTION_ID);
    }
    panel.setCloseAction(() -> HeicViews.hideDownscaleBanner(hideKey));
    return panel;
  }

  /** The banner text: the size shown and the size of the image, and why. */
  static @NotNull String text(@NotNull Downscales.Entry entry) {
    String shown = entry.shownWidth() + "x" + entry.shownHeight();
    String full = entry.width() + "x" + entry.height();
    return HeicBundle.message(entry.isHeapLimited() ? "downscale.banner" : "downscale.banner.array", shown, full);
  }
}
