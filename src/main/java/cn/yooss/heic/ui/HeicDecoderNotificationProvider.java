package cn.yooss.heic.ui;

import cn.yooss.heic.HeicBundle;
import cn.yooss.heic.HeicImageReaderSpi;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.awt.RelativePoint;
import org.intellij.images.editor.ImageFileEditor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Point;
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
 * Dynamic unload: IntelliJ 2024.1 to 2025.x remove this provider's panels from open editors when the extension is
 * removed, 2026.1 and newer do not; {@link HeicViews#shutDown()} removes them in {@code beforePluginUnload}. The function
 * returned by {@link #collectNotificationData} is applied later, in a separate EDT step of the platform's update job,
 * which may run after {@code beforePluginUnload} (the platform flushes the event queue right after it): it checks
 * {@link HeicViews#isBannerHidden} again when applied, so no panel is created after the shutdown (both run on the EDT).
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
    HeifBackendStatus.Reason reason = remedy.reason();
    // Applied later on the EDT: not after the plugin's shutdown (the panel would keep the plugin class loader alive),
    // and not for a banner the user closed meanwhile.
    return editor -> editor instanceof ImageFileEditor && !HeicViews.isBannerHidden(reason)
                     ? createPanel(editor, project, remedy) : null;
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

  /**
   * The actions that do not fit into the banner, in a popup below the "More" link, right-aligned with it (the link is at
   * the right end of the banner: a popup starting at the link would reach beyond the editor). The popup is closed with
   * the banners and before the plugin is unloaded ({@link RemedyActions#popupShown}).
   */
  private static void showMore(@NotNull List<HeifRemedy.Action> actions, @Nullable Project project, @NotNull JComponent anchor) {
    List<String> labels = new ArrayList<>();
    for (HeifRemedy.Action action : actions) labels.add(HeicBundle.message(action.textKey()));
    JBPopup popup = JBPopupFactory.getInstance().createPopupChooserBuilder(labels)
      .setItemChosenCallback(label -> RemedyActions.perform(actions.get(labels.indexOf(label)), project, anchor))
      .createPopup();
    if (!RemedyActions.popupShown(popup)) return;
    int width = popup.getContent().getPreferredSize().width;
    popup.show(new RelativePoint(anchor, new Point(Math.min(0, anchor.getWidth() - width), anchor.getHeight())));
  }

  /**
   * The banner text: the short title and, where copying it is the remedy (the first action, i.e. the package command on
   * Linux), the command that installs the missing component, {@linkplain #abbreviate abbreviated} in the middle when it
   * is long (Copy Command next to it copies all of it). Plain text, so that the label ends with "..." when the editor is
   * narrow; the explanation and the whole command are its tooltip (and the content of the balloons). A Flatpak IDE's
   * command is run in a terminal on the host ({@link HeifRemedy#isHostCommand()}). On Windows the Microsoft Store comes
   * first, and the winget command is only in the tooltip, the balloons and "More".
   */
  static @NotNull String text(@NotNull HeifRemedy remedy) {
    String title = HeicBundle.message(remedy.titleKey());
    String command = remedy.command();
    boolean commandFirst = command != null && !remedy.actions().isEmpty()
                           && remedy.actions().get(0).type() == HeifRemedy.ActionType.COPY_COMMAND;
    if (!commandFirst) return title;
    String key = remedy.isHostCommand() ? "remedy.banner.command" + HeifRemedy.HOST_SUFFIX : "remedy.banner.command";
    int room = Math.max(MIN_BANNER_COMMAND, MAX_BANNER_TEXT - HeicBundle.message(key, title, "").length());
    return HeicBundle.message(key, title, abbreviate(command, room));
  }

  /**
   * The banner text is kept to about this many characters (with the command abbreviated): about what a banner of an
   * editor in a 1500-pixel window shows before its links.
   */
  static final int MAX_BANNER_TEXT = 108;
  /** A command is abbreviated to no fewer characters than this, however long the title. */
  static final int MIN_BANNER_COMMAND = 40;

  /**
   * {@code command} if it has at most {@code max} characters, otherwise its first words and its end, joined by
   * {@code " … "}, in at most {@code max} characters: e.g. {@code flatpak install flathub … codecs-extra//25.08-extra}.
   * The start keeps whole words (at most half of {@code max}); the end starts at a word if one fits, else after a dot or
   * a slash, else anywhere.
   */
  static @NotNull String abbreviate(@NotNull String command, int max) {
    if (command.length() <= max) return command;
    String separator = " \u2026 ";
    int head = 0;
    for (int space = command.indexOf(' '); space > 0 && space <= max / 2; space = command.indexOf(' ', space + 1)) head = space;
    if (head == 0) head = max / 2;
    String start = command.substring(0, head).trim();
    int from = Math.max(head + 1, command.length() - (max - start.length() - separator.length()));
    int cut = boundary(command, from, " ");
    if (cut < 0) cut = boundary(command, from, "./");
    String end = command.substring(cut < 0 ? from : cut).trim();
    return end.isEmpty() ? start + separator.trim() : start + separator + end;
  }

  /** The first index at or after {@code from} that follows one of {@code separators} (and is not the end), or -1. */
  private static int boundary(String text, int from, String separators) {
    for (int i = from; i < text.length(); i++) {
      if (separators.indexOf(text.charAt(i - 1)) >= 0) return i;
    }
    return -1;
  }

  private static EditorNotificationPanel.Status panelStatus(HeifBackendStatus.Reason reason) {
    if (reason == HeifBackendStatus.Reason.ERROR) return EditorNotificationPanel.Status.Error;
    return reason.isUserInstallable() ? EditorNotificationPanel.Status.Warning : EditorNotificationPanel.Status.Info;
  }
}
