package cn.yooss.heic.ui;

import cn.yooss.heic.HeicBundle;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.datatransfer.StringSelection;
import java.lang.ref.WeakReference;

/**
 * Performs a {@link HeifRemedy.Action} of the banner or of a notification (EDT).
 * <p>
 * The "copied" confirmation balloon and the banner's "More" popup reference plugin classes, so {@link #closePopups()}
 * closes them immediately, without the fade-out, when the banners go away and before the plugin is unloaded. They are
 * referenced weakly: a closed balloon still references its frame and so the frame's project.
 */
final class RemedyActions {
  private static final Logger LOG = Logger.getInstance(RemedyActions.class);
  /** How long the "copied" confirmation next to the clicked link stays. */
  private static final long COPIED_FADEOUT_MILLIS = 5000;
  private static final Object LOCK = new Object();
  /** The "copied" confirmation on screen, if any (guarded by {@link #LOCK}). */
  private static @Nullable WeakReference<Balloon> copiedBalloon;
  /** The "More" popup of a banner on screen, if any (guarded by {@link #LOCK}). */
  private static @Nullable WeakReference<JBPopup> morePopup;
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
        case CHECK_AGAIN:
          DecoderStatus.recheck(project);
          break;
      }
    }
    catch (RuntimeException e) {
      LOG.warn("Cannot perform " + action, e);
    }
  }

  /** A short confirmation below the clicked link: what was copied and what to do next. */
  private static void showCopied(@Nullable Component anchor, @NotNull String command) {
    if (!(anchor instanceof JComponent) || !anchor.isShowing()) return;
    String key = HeifRemedies.isHostCommand(command) ? "remedy.command.copied" + HeifRemedy.HOST_SUFFIX : "remedy.command.copied";
    String text = HeicBundle.message(key, "<code>" + StringUtil.escapeXmlEntities(command) + "</code>",
                                     HeicBundle.message("remedy.action.check.again"));
    Balloon balloon = JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
      .setFadeoutTime(COPIED_FADEOUT_MILLIS)
      .createBalloon();
    Balloon previous;
    boolean unloading;
    synchronized (LOCK) {
      unloading = shutDown;
      previous = unloading ? null : get(copiedBalloon);
      if (!unloading) copiedBalloon = new WeakReference<>(balloon);
    }
    if (unloading) {
      Disposer.dispose(balloon);
      return;
    }
    if (previous != null) close(previous);
    balloon.show(RelativePoint.getSouthOf((JComponent) anchor), Balloon.Position.below);
  }

  /**
   * EDT. The "More" popup of a banner is about to be shown: remembered, so that it is closed with the banners (a previous
   * one is closed now). {@code false} if the plugin is being unloaded (the popup is then disposed and must not be shown).
   */
  static boolean popupShown(@NotNull JBPopup popup) {
    JBPopup previous;
    boolean unloading;
    synchronized (LOCK) {
      unloading = shutDown;
      previous = unloading ? null : get(morePopup);
      if (!unloading) morePopup = new WeakReference<>(popup);
    }
    if (unloading) {
      Disposer.dispose(popup);
      return false;
    }
    if (previous != null && previous != popup) close(previous);
    return true;
  }

  /**
   * EDT. Closes the "copied" confirmation and the "More" popup right away (the decoder became available and the banners
   * are going away, or the plugin is being unloaded).
   */
  static void closePopups() {
    Balloon balloon;
    JBPopup popup;
    synchronized (LOCK) {
      balloon = get(copiedBalloon);
      popup = get(morePopup);
      copiedBalloon = null;
      morePopup = null;
    }
    try {
      if (balloon != null) close(balloon);
    }
    finally {
      if (popup != null) close(popup);
    }
  }

  /**
   * Hides {@code balloon} without animation and disposes it now, and with it the position tracker that the frame
   * references (also if a fade-out had already started, which would dispose it only when it ends).
   */
  static void close(@NotNull Balloon balloon) {
    try {
      balloon.hideImmediately();
    }
    finally {
      Disposer.dispose(balloon);
    }
  }

  /** Closes {@code popup} (and its window) now. */
  static void close(@NotNull JBPopup popup) {
    try {
      popup.cancel();
    }
    finally {
      if (!popup.isDisposed()) Disposer.dispose(popup);
    }
  }

  private static <T> @Nullable T get(@Nullable WeakReference<T> reference) {
    return reference != null ? reference.get() : null;
  }

  /** Before the plugin is unloaded (EDT): no more confirmations or popups, and those on screen are closed. */
  static void shutDown() {
    synchronized (LOCK) {
      shutDown = true;
    }
    closePopups();
  }

  @TestOnly
  static void resetForTests() {
    closePopups();
    synchronized (LOCK) {
      shutDown = false;
    }
  }

  /** Tests: the "copied" confirmation and the "More" popup that would be closed, if they are still referenced. */
  @TestOnly
  static @Nullable Object @NotNull [] trackedPopupsForTests() {
    synchronized (LOCK) {
      return new Object[]{get(copiedBalloon), get(morePopup)};
    }
  }

  /** Tests: remembers {@code balloon} like a "copied" confirmation that was shown. */
  @TestOnly
  static void trackCopiedBalloonForTests(@NotNull Balloon balloon) {
    synchronized (LOCK) {
      copiedBalloon = new WeakReference<>(balloon);
    }
  }
}
