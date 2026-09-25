package cn.yooss.heic.ui;

import cn.yooss.heic.HeicBundle;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.datatransfer.StringSelection;

/**
 * Performs a {@link HeifRemedy.Action} of the banner or of a notification (EDT).
 * <p>
 * The "copied" confirmation is a balloon anchored to the clicked link; the platform tracks the link's position through
 * a listener on the frame, which references the link and, through it, the banner with its plugin-class actions. The
 * balloon is therefore hidden when the banners go away ({@link #hideCopiedBalloon()}) and before the plugin is unloaded.
 */
final class RemedyActions {
  private static final Logger LOG = Logger.getInstance(RemedyActions.class);
  /** How long the "copied" confirmation next to the clicked link stays. */
  private static final long COPIED_FADEOUT_MILLIS = 5000;
  private static final Object LOCK = new Object();
  /** The "copied" confirmation on screen, if any (guarded by {@link #LOCK}). */
  private static Balloon copiedBalloon;
  private static boolean shutDown;

  private RemedyActions() {
  }

  /**
   * @param project the project of the banner or notification, if any
   * @param anchor  the clicked link, for the "copied" confirmation; {@code null} if unknown
   */
  static void perform(@NotNull HeifRemedy.Action action, @Nullable Project project, @Nullable Component anchor) {
    String target = action.target();
    try {
      switch (action.type()) {
        case OPEN_URL:
        case LEARN_MORE:
          // Checked again here: never open anything but https: and Microsoft Store links.
          if (HeifRemedies.isAllowedUrl(target)) BrowserUtil.browse(target);
          break;
        case COPY_COMMAND:
          if (target == null) break;
          CopyPasteManager.getInstance().setContents(new StringSelection(target));
          showCopied(anchor, target);
          break;
        case OPEN_SETTINGS:
          if (target != null) ShowSettingsUtil.getInstance().showSettingsDialog(project, configurable -> hasId(configurable, target), null);
          break;
        case CHECK_AGAIN:
          DecoderStatus.recheck(DecoderStatus.Trigger.USER, project);
          break;
      }
    }
    catch (RuntimeException e) {
      LOG.warn("Cannot perform " + action, e);
    }
    // The user may install the component outside the IDE now: check again when the IDE is activated.
    if (action.type().startsInstallation()) DecoderStatus.remedyActionPerformed();
  }

  private static boolean hasId(Configurable configurable, String id) {
    return configurable instanceof SearchableConfigurable && id.equals(((SearchableConfigurable) configurable).getId());
  }

  /** A short confirmation below the clicked link: what was copied and what to do next. */
  private static void showCopied(@Nullable Component anchor, @NotNull String command) {
    if (!(anchor instanceof JComponent) || !anchor.isShowing()) return;
    String text = HeicBundle.message("remedy.command.copied", "<code>" + StringUtil.escapeXmlEntities(command) + "</code>",
                                     HeicBundle.message("remedy.action.check.again"));
    Balloon balloon = JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
      .setFadeoutTime(COPIED_FADEOUT_MILLIS)
      .createBalloon();
    Balloon previous;
    synchronized (LOCK) {
      if (shutDown) return;
      previous = copiedBalloon;
      copiedBalloon = balloon;
    }
    if (previous != null) previous.hide();
    balloon.show(RelativePoint.getSouthOf((JComponent) anchor), Balloon.Position.below);
  }

  /** EDT. Hides the "copied" confirmation (the decoder became available: its link is going away). */
  static void hideCopiedBalloon() {
    Balloon balloon;
    synchronized (LOCK) {
      balloon = copiedBalloon;
      copiedBalloon = null;
    }
    if (balloon != null) balloon.hide();
  }

  /** Before the plugin is unloaded (EDT): no more confirmations, and the one on screen is hidden. */
  static void shutDown() {
    synchronized (LOCK) {
      shutDown = true;
    }
    hideCopiedBalloon();
  }

  @TestOnly
  static void resetForTests() {
    hideCopiedBalloon();
    synchronized (LOCK) {
      shutDown = false;
    }
  }
}
