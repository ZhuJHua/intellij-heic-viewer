package cn.yooss.heic.ui;

import cn.yooss.heic.HeicBundle;
import cn.yooss.heic.HeicImageReaderSpi;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.HyperlinkLabel;
import org.intellij.images.editor.ImageFileEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The banner above a HEIC/HEIF image editor while the system decoder is unavailable: what is missing
 * ({@code remedy.title.<REASON>}, with the install command where copying it is the remedy; the explanation
 * {@code backend.status.<REASON>} is the tooltip) and the remedy's actions (Microsoft Store, copy command, Check Again,
 * Learn More, ...), see {@link HeifRemedies}; beyond {@value #MAX_LINKS} links, the last ones are under "More".
 * <p>
 * {@link #collectNotificationData} runs on a background thread under a read action (IntelliJ 2024.1 to 2026.2), and must
 * be cheap: it only compares the file extension and reads the decoder's cached status. If the decoder has not been
 * probed yet, it starts a probe on a pooled thread and shows nothing; a probe that finds the decoder missing updates
 * the banners ({@link DecoderStatus}). The panel itself is created on the EDT. When the decoder becomes available
 * ("Check Again"), the banners are updated away and the editors reload their image ({@link HeicViews}).
 * <p>
 * Dynamic unload: the platform removes this provider's panels from open editors when the extension is removed; after
 * {@code beforePluginUnload} no new panel is created.
 */
public final class HeicDecoderNotificationProvider implements EditorNotificationProvider, DumbAware {
  @Override
  public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(@NotNull Project project,
                                                                                            @NotNull VirtualFile file) {
    if (!HeicImageReaderSpi.isHeicExtension(file.getExtension())) return null;
    HeifBackendStatus status = DecoderStatus.cached();
    if (status == null) {
      DecoderStatus.status(); // probes on a pooled thread; updates the banners if the decoder is missing
      return null;
    }
    if (status.isAvailable() || HeicViews.isBannerHidden(status.reason())) return null;
    HeifRemedy remedy = HeifRemedies.forStatus(status);
    if (remedy == null) return null;
    return editor -> editor instanceof ImageFileEditor ? createPanel(editor, project, remedy) : null;
  }

  /** At most this many links; with more actions, the first {@code MAX_LINKS - 1} and "More" (a popup with the rest). */
  static final int MAX_LINKS = 3;

  /** EDT. */
  static @NotNull EditorNotificationPanel createPanel(@Nullable FileEditor editor, @Nullable Project project,
                                                     @NotNull HeifRemedy remedy) {
    EditorNotificationPanel panel = new EditorNotificationPanel(editor, panelStatus(remedy.reason()));
    panel.setText(text(remedy));
    panel.setToolTipText("<html>" + DecoderPrompt.content(remedy) + "</html>");
    List<HeifRemedy.Action> actions = remedy.actions();
    int shown = actions.size() <= MAX_LINKS ? actions.size() : MAX_LINKS - 1;
    for (HeifRemedy.Action action : actions.subList(0, shown)) {
      HyperlinkLabel[] link = new HyperlinkLabel[1];
      link[0] = panel.createActionLabel(HeicBundle.message(action.textKey()), () -> RemedyActions.perform(action, project, link[0]));
    }
    if (shown < actions.size()) {
      List<HeifRemedy.Action> more = actions.subList(shown, actions.size());
      HyperlinkLabel[] link = new HyperlinkLabel[1];
      link[0] = panel.createActionLabel(HeicBundle.message("remedy.action.more"), () -> showMore(more, project, link[0]), false);
    }
    HeifBackendStatus.Reason reason = remedy.reason();
    panel.setCloseAction(() -> HeicViews.hideBanners(reason));
    return panel;
  }

  /** The actions that do not fit into the banner, in a popup below the "More" link. */
  private static void showMore(@NotNull List<HeifRemedy.Action> actions, @Nullable Project project, @NotNull JComponent anchor) {
    List<String> labels = new ArrayList<>();
    for (HeifRemedy.Action action : actions) labels.add(HeicBundle.message(action.textKey()));
    JBPopupFactory.getInstance().createPopupChooserBuilder(labels)
      .setItemChosenCallback(label -> RemedyActions.perform(actions.get(labels.indexOf(label)), project, anchor))
      .createPopup()
      .showUnderneathOf(anchor);
  }

  /**
   * The banner text: the short title and, where copying it is the remedy (the first action, i.e. the package command on
   * Linux), the command that installs the missing component. Plain text, so that the label ends with "..." when the
   * editor is narrow; the explanation and the command are its tooltip (and the content of the balloons). On Windows the
   * Microsoft Store comes first, and the winget command is only in the tooltip, the balloons and "More".
   */
  static @NotNull String text(@NotNull HeifRemedy remedy) {
    String title = HeicBundle.message(remedy.titleKey());
    String command = remedy.command();
    boolean commandFirst = command != null && !remedy.actions().isEmpty()
                           && remedy.actions().get(0).type() == HeifRemedy.ActionType.COPY_COMMAND;
    return commandFirst ? HeicBundle.message("remedy.banner.command", title, command) : title;
  }

  private static EditorNotificationPanel.Status panelStatus(HeifBackendStatus.Reason reason) {
    if (reason == HeifBackendStatus.Reason.ERROR) return EditorNotificationPanel.Status.Error;
    return reason.isUserInstallable() ? EditorNotificationPanel.Status.Warning : EditorNotificationPanel.Status.Info;
  }
}
