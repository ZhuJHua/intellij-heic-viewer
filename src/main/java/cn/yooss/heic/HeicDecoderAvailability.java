package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.thumbnail.HeicThumbnails;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Tells the user when the system HEIF decoder is missing and how to get it.
 * <p>
 * After the reader is registered (IDE start, plugin installation), {@link #checkInBackground()} probes the
 * {@link HeifBackend} of the running OS on a pooled thread. If its {@link HeifBackendStatus} is
 * {@linkplain HeifBackendStatus#isUserInstallable() user-installable} (e.g. the Windows HEIF/HEVC extensions or the
 * Linux libheif packages are missing), a notification explains what is missing ({@code backend.status.<REASON>} in
 * {@link HeicBundle}) and offers the backend's {@linkplain HeifBackendStatus#installUrl() install page},
 * {@linkplain HeifBackendStatus#installCommand() install command} (copied to the clipboard), "Check Again" (probes
 * again; once the decoder is available, HEIC files load without a restart) and "Don't Show Again" (per reason). Other
 * unavailable states (no backend for this OS, not implemented yet, errors) are only logged.
 * <p>
 * A normal start where the decoder is available posts nothing to the EDT. The notification's actions are plugin
 * classes, so {@link #shutDown()} expires it before the plugin is unloaded.
 */
public final class HeicDecoderAvailability {
  private static final Logger LOG = Logger.getInstance(HeicDecoderAvailability.class);
  /** Notification group declared in plugin.xml. */
  public static final String NOTIFICATION_GROUP = "HEIC Viewer";
  /** Prefix of the application-level {@link PropertiesComponent} flag set by "Don't Show Again" (+ reason name). */
  static final String DISMISSED_KEY_PREFIX = "heic.viewer.decoder.prompt.dismissed.";

  private static final AtomicBoolean checkStarted = new AtomicBoolean();
  private static final Object LOCK = new Object();
  private static Notification shown;
  private static boolean shutDown;

  private HeicDecoderAvailability() {
  }

  /** Probes the system decoder on a pooled thread (once per plugin class loader) and prompts if it is missing. */
  public static void checkInBackground() {
    Application application = ApplicationManager.getApplication();
    if (application == null || application.isDisposed() || application.isUnitTestMode()
        || application.isHeadlessEnvironment()) {
      return;
    }
    if (!checkStarted.compareAndSet(false, true)) return;
    AppExecutorUtil.getAppExecutorService().execute(() -> check(false));
  }

  /** Before the plugin is unloaded: expires the notification and shows no new one. */
  public static void shutDown() {
    Notification notification;
    synchronized (LOCK) {
      shutDown = true;
      notification = shown;
      shown = null;
    }
    if (notification != null) notification.expire();
  }

  /** Whether the install prompt is shown for {@code status}. */
  static boolean shouldPrompt(@NotNull HeifBackendStatus status, @NotNull Predicate<HeifBackendStatus.Reason> dismissed) {
    HeifBackendStatus.Reason reason = status.reason();
    return reason != null && reason.isUserInstallable() && !dismissed.test(reason);
  }

  /** Notification text: what is missing (HTML) and, if the backend knows it, the command that installs it. */
  static @NotNull String promptContent(@NotNull HeifBackendStatus status) {
    HeifBackendStatus.Reason reason = status.reason();
    StringBuilder content = new StringBuilder(HeicBundle.message(
      reason != null ? reason.bundleKey() : HeifBackendStatus.Reason.ERROR.bundleKey()));
    if (status.installCommand() != null) {
      content.append("<br>").append(HeicBundle.message("decoder.missing.command"))
        .append("<br><code>").append(StringUtil.escapeXmlEntities(status.installCommand())).append("</code>");
    }
    return content.toString();
  }

  /** Runs on a pooled thread. */
  private static void check(boolean recheck) {
    try {
      HeifBackend backend = HeifBackends.current();
      HeifBackendStatus status = recheck ? backend.recheckStatus() : backend.status();
      if (status.isAvailable()) {
        LOG.info("HEIC decoder: " + status.detail());
        if (recheck) {
          HeicThumbnails.refreshAll(); // icons that failed while the decoder was missing
          show(HeicBundle.message("decoder.available.title"), HeicBundle.message("decoder.available.content"),
               NotificationType.INFORMATION, null);
        }
        return;
      }
      if (status.reason() == HeifBackendStatus.Reason.ERROR) LOG.warn("HEIC decoder: " + status);
      else LOG.info("HEIC decoder: " + status);
      if (recheck ? status.isUserInstallable() : shouldPrompt(status, HeicDecoderAvailability::isDismissed)) {
        show(HeicBundle.message("decoder.missing.title"), promptContent(status), NotificationType.WARNING, status);
      }
    }
    catch (RuntimeException | LinkageError e) {
      LOG.warn("Cannot check the HEIC decoder", e);
    }
  }

  private static void show(@NotNull String title, @NotNull String content, @NotNull NotificationType type,
                           HeifBackendStatus missing) {
    Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(title, content, type);
    if (missing != null) {
      String url = missing.installUrl();
      if (url != null) {
        notification.addAction(NotificationAction.createSimple(HeicBundle.message("decoder.action.install"),
                                                               () -> BrowserUtil.browse(url)));
      }
      String command = missing.installCommand();
      if (command != null) {
        notification.addAction(NotificationAction.createSimple(
          HeicBundle.message("decoder.action.copy.command"),
          () -> CopyPasteManager.getInstance().setContents(new StringSelection(command))));
      }
      notification.addAction(NotificationAction.createSimpleExpiring(
        HeicBundle.message("decoder.action.check.again"),
        () -> AppExecutorUtil.getAppExecutorService().execute(() -> check(true))));
      HeifBackendStatus.Reason reason = missing.reason();
      if (reason != null) {
        notification.addAction(NotificationAction.createSimpleExpiring(
          HeicBundle.message("decoder.action.dont.show.again"),
          () -> PropertiesComponent.getInstance().setValue(DISMISSED_KEY_PREFIX + reason.name(), true)));
      }
    }
    Notification previous;
    synchronized (LOCK) {
      if (shutDown) return;
      previous = shown;
      shown = notification;
    }
    if (previous != null) previous.expire();
    notification.notify(null);
  }

  private static boolean isDismissed(HeifBackendStatus.Reason reason) {
    try {
      return PropertiesComponent.getInstance().getBoolean(DISMISSED_KEY_PREFIX + reason.name());
    }
    catch (RuntimeException | LinkageError e) {
      return false;
    }
  }
}
