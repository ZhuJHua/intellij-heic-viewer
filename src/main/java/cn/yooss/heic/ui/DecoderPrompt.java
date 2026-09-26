package cn.yooss.heic.ui;

import cn.yooss.heic.HeicBundle;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The balloons (notification group {@value #NOTIFICATION_GROUP}, declared in plugin.xml):
 * <ul>
 *   <li><b>Decoder missing</b>, at most once per session: when a HEIC image fails to load where no banner explains why
 *   (the diff viewer), and right after the plugin was installed. It offers the remedy's actions.</li>
 *   <li><b>Result of "Check Again"</b>: available now, or still missing (with the actions again).</li>
 * </ul>
 * {@link #shutDown()} expires the notifications on screen before the plugin is unloaded (their actions are plugin
 * classes). They are kept in a static list for that, so their actions take the project from the action event instead of
 * capturing it: a closed project's balloons are hidden, not expired.
 */
public final class DecoderPrompt {
  private static final Logger LOG = Logger.getInstance(DecoderPrompt.class);
  /** Notification group declared in plugin.xml. */
  public static final String NOTIFICATION_GROUP = "HEIC Viewer";

  private static final Object LOCK = new Object();
  /** Whether the "decoder missing" balloon was shown in this session (guarded by {@link #LOCK}). */
  private static boolean missingShown;
  /** Notifications that may still be on screen (guarded by {@link #LOCK}). */
  private static final List<Notification> shown = new ArrayList<>();
  private static boolean shutDown;

  private DecoderPrompt() {
  }

  /**
   * A HEIC image could not be decoded because the system decoder is unavailable, where no banner tells why (the diff
   * viewer): shows the "decoder missing" balloon unless it was shown in this session. Any thread.
   */
  public static void decodeUnavailable(@NotNull HeifBackendStatus status, @Nullable Project project) {
    showMissing(status, project);
  }

  /** The "decoder missing" balloon, once per session. Any thread. */
  static void showMissing(@NotNull HeifBackendStatus status, @Nullable Project project) {
    HeifRemedy remedy = HeifRemedies.forStatus(status);
    if (remedy == null) return;
    synchronized (LOCK) {
      if (missingShown || shutDown) return;
      missingShown = true;
    }
    show(HeicBundle.message(remedy.titleKey()), content(remedy), NotificationType.WARNING, remedy, project);
  }

  /** "Check Again" found the decoder, or the decoder appeared after a remedy action. Any thread. */
  static void showAvailable(@Nullable Project project) {
    show(HeicBundle.message("remedy.check.available.title"), HeicBundle.message("remedy.check.available.content"),
         NotificationType.INFORMATION, null, project);
  }

  /** "Check Again" did not find the decoder: what is missing (maybe something else now) and the actions again. */
  static void showStillMissing(@NotNull HeifBackendStatus status, @Nullable Project project) {
    HeifRemedy remedy = HeifRemedies.forStatus(status);
    if (remedy == null) return;
    String content = "<b>" + HeicBundle.message(remedy.titleKey()) + "</b><br>" + content(remedy);
    if (isStoreExtension(remedy.reason())) {
      // A running process may not see a Store package installed after it started: say what else helps.
      content += "<br>" + HeicBundle.message("remedy.check.missing.restart");
    }
    show(HeicBundle.message("remedy.check.missing.title"), content, NotificationType.WARNING, remedy, project);
  }

  private static boolean isStoreExtension(HeifBackendStatus.Reason reason) {
    return reason == HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING
           || reason == HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING;
  }

  /** Notification content: the explanation (HTML) and, if there is one, the command that installs the component. */
  static @NotNull String content(@NotNull HeifRemedy remedy) {
    StringBuilder content = new StringBuilder(HeicBundle.message(remedy.explanationKey()));
    String command = remedy.command();
    if (command != null) {
      content.append("<br>").append(HeicBundle.message(remedy.isHostCommand() ? "remedy.command.label" + HeifRemedy.HOST_SUFFIX
                                                                              : "remedy.command.label"))
        .append("<br><code>").append(StringUtil.escapeXmlEntities(command)).append("</code>");
    }
    return content.toString();
  }

  private static void show(@NotNull String title, @NotNull String content, @NotNull NotificationType type,
                           @Nullable HeifRemedy remedy, @Nullable Project project) {
    Notification notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification(title, content, type);
    if (remedy != null) {
      for (HeifRemedy.Action action : remedy.actions()) {
        String text = HeicBundle.message(action.textKey());
        if (action.type() == HeifRemedy.ActionType.CHECK_AGAIN) {
          // The result is reported in a new balloon.
          notification.addAction(NotificationAction.createExpiring(text, (event, n) -> RemedyActions.perform(action, event.getProject(), null)));
        }
        else {
          notification.addAction(NotificationAction.create(text, (event, n) -> RemedyActions.perform(action, event.getProject(), source(event))));
        }
      }
    }
    // Not kept once it expired (e.g. "Check Again" in it was clicked).
    notification.whenExpired(() -> {
      synchronized (LOCK) {
        shown.remove(notification);
      }
    });
    List<Notification> previous;
    synchronized (LOCK) {
      if (shutDown) return;
      previous = new ArrayList<>(shown);
      shown.clear();
      shown.add(notification);
    }
    for (Notification old : previous) old.expire(); // the newest balloon replaces the older ones
    LOG.info("Showing the HEIC Viewer notification: " + title + (remedy != null ? " (" + remedy.reason() + ")" : ""));
    notification.notify(project != null && !project.isDisposed() ? project : null);
  }

  private static @Nullable Component source(@Nullable AnActionEvent event) {
    InputEvent input = event != null ? event.getInputEvent() : null;
    return input != null ? input.getComponent() : null;
  }

  /** Before the plugin is unloaded: expires the notifications and shows no new ones. */
  static void shutDown() {
    List<Notification> notifications;
    synchronized (LOCK) {
      shutDown = true;
      notifications = new ArrayList<>(shown);
      shown.clear();
    }
    for (Notification notification : notifications) notification.expire();
  }

  /** Tests: the list of notifications that may still be on screen (live). */
  @TestOnly
  static @NotNull Object shownNotificationsForTests() {
    return shown;
  }

  @TestOnly
  static void resetForTests() {
    List<Notification> notifications;
    synchronized (LOCK) {
      missingShown = false;
      shutDown = false;
      notifications = new ArrayList<>(shown);
      shown.clear();
    }
    for (Notification notification : notifications) notification.expire();
  }
}
