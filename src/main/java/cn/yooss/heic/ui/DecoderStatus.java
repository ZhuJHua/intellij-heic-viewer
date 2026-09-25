package cn.yooss.heic.ui;

import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackends;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The system decoder's {@link HeifBackendStatus} as the UI sees it. <b>Never probes on the calling thread</b>: the probe
 * may load native libraries, so it runs on a pooled thread, one at a time; the EDT and the editor notification
 * provider only read the backend's {@linkplain HeifBackend#cachedStatus() cached status}.
 * <ul>
 *   <li>{@link #status()}: the cached status, or a probe on a pooled thread. When a probe finds the decoder missing,
 *   the editor banners are updated (they were collected while the status was unknown and showed nothing).</li>
 *   <li>{@link #recheck}: "Check Again", and the automatic re-check when the IDE window is activated after the user
 *   clicked a remedy action (Microsoft Store, copied command, ...): forgets the cached status and probes again. If the
 *   decoder is now available, banners disappear, open HEIC editors reload and thumbnails are decoded again
 *   ({@link HeicViews#decoderBecameAvailable()}), so no restart is needed.</li>
 * </ul>
 * A normal start where the decoder is available (e.g. every macOS start) posts nothing to the EDT: on Java 17
 * (IntelliJ 2024.1) an EDT event created by plugin code while the IDE starts can keep the plugin class loader alive
 * (see {@code HeicFileTypeMappingRepair}).
 */
public final class DecoderStatus {
  private static final Logger LOG = Logger.getInstance(DecoderStatus.class);
  /** After a remedy action, activating the IDE re-checks the decoder for this long. */
  static final long ACTIVATION_CHECK_MILLIS = TimeUnit.MINUTES.toMillis(30);
  /** Minimum time between two re-checks on activation. */
  static final long ACTIVATION_DEBOUNCE_MILLIS = 3000;

  /** Who asked for a re-check. */
  enum Trigger {
    /** "Check Again": the result is always reported. */
    USER,
    /** The IDE window was activated after a remedy action: only a decoder that became available is reported. */
    ACTIVATION
  }

  /** What {@link #status()} answers while the plugin is being unloaded. */
  private static final HeifBackendStatus SHUT_DOWN =
    HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "HEIC Viewer is being unloaded");

  private static final Object LOCK = new Object();
  /** The first probe of the current backend (guarded by {@link #LOCK}). */
  private static CompletableFuture<HeifBackendStatus> firstProbe;
  private static final AtomicBoolean startupCheck = new AtomicBoolean();
  private static final AtomicBoolean rechecking = new AtomicBoolean();
  /** A "Check Again" not answered yet: it may come while a re-check (e.g. on activation) runs. */
  private static final AtomicBoolean userRecheckPending = new AtomicBoolean();
  /** The project of the last "Check Again", for its balloon (weakly: a static field must not keep a closed project). */
  private static volatile @Nullable WeakReference<Project> userRecheckProject;
  /** Until when (epoch millis) activating the IDE re-checks the decoder; 0: not armed. */
  private static volatile long activationCheckUntil;
  private static volatile long lastActivationCheck;
  private static volatile boolean shutDown;

  private DecoderStatus() {
  }

  /**
   * The status the current backend has cached, or {@code null} if it has not been probed yet (or the plugin is being
   * unloaded: the backend is not even looked up then, it is released right after). Never probes; any thread.
   */
  public static @Nullable HeifBackendStatus cached() {
    return shutDown ? null : HeifBackends.current().cachedStatus();
  }

  /**
   * The decoder's status: completed at once if it is known, otherwise after a probe on a pooled thread (a single probe,
   * however many callers). Never probes on the calling thread. Dependent actions run on the pooled thread, or on the
   * calling thread if the status is known.
   */
  public static @NotNull CompletableFuture<HeifBackendStatus> status() {
    if (shutDown) return CompletableFuture.completedFuture(SHUT_DOWN);
    HeifBackendStatus known = cached();
    if (known != null) return CompletableFuture.completedFuture(known);
    synchronized (LOCK) {
      // Once only: a backend that does not cache its status gets the first result (and no probe loop through the banners).
      if (firstProbe == null) {
        CompletableFuture<HeifBackendStatus> probe = new CompletableFuture<>();
        firstProbe = probe;
        boolean started = execute(() -> {
          try {
            probe.complete(probe());
          }
          finally {
            probe.complete(HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "The probe failed"));
          }
        });
        if (!started) probe.complete(HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "The probe could not be started"));
      }
      return firstProbe;
    }
  }

  /** Pooled thread. */
  private static HeifBackendStatus probe() {
    HeifBackendStatus status = HeifBackends.current().status();
    log("HEIC decoder: ", status);
    // Banners are collected while the status is unknown (they show nothing then): show them now.
    if (!status.isAvailable()) HeicViews.updateBanners();
    return status;
  }

  /**
   * After the reader has been registered: probes on a pooled thread (the result is logged, and the banners of HEIC
   * files that are already open are updated). {@code prompt} (the plugin was just installed or updated): a balloon also
   * tells right away when the decoder is missing. Otherwise the user is told where a HEIC image fails to load: the
   * banner of the image editor, or one balloon per session for the diff viewer and the thumbnails.
   */
  public static void checkInBackground(boolean prompt) {
    Application application = ApplicationManager.getApplication();
    if (application == null || application.isDisposed() || application.isUnitTestMode()
        || application.isHeadlessEnvironment()) {
      return;
    }
    if (!startupCheck.compareAndSet(false, true)) return;
    CompletableFuture<HeifBackendStatus> status = status();
    boolean known = status.isDone(); // otherwise the probe updates the banners itself
    status.thenAccept(result -> {
      if (result.isAvailable()) return; // the normal case: nothing to show, nothing posted to the EDT
      // HEIC files that are already open (e.g. while the plugin is installed or updated) get their banner.
      if (known) HeicViews.updateBanners();
      if (prompt) DecoderPrompt.showMissing(result, null);
    });
  }

  /**
   * Forgets the cached status and probes again on a pooled thread, one re-check at a time. A decoder that became
   * available refreshes every view ({@link HeicViews#decoderBecameAvailable()}) and is reported in a balloon; the result
   * of {@link Trigger#USER "Check Again"} is always reported. A "Check Again" while a re-check runs (e.g. the one on
   * activation, when the user comes back to the IDE by clicking it) is answered by that re-check if it has not probed
   * yet, otherwise by one more probe right after it; an activation re-check while one runs is dropped.
   */
  static void recheck(@NotNull Trigger trigger, @Nullable Project project) {
    if (shutDown) return;
    if (trigger == Trigger.USER) {
      userRecheckProject = project != null ? new WeakReference<>(project) : null;
      userRecheckPending.set(true); // before the compareAndSet: a running re-check sees it at the latest in its finally
    }
    if (!rechecking.compareAndSet(false, true)) return;
    boolean started = execute(() -> {
      try {
        boolean user = userRecheckPending.getAndSet(false);
        runRecheck(user ? Trigger.USER : trigger, user ? userRecheckProject() : project);
      }
      finally {
        rechecking.set(false);
        if (userRecheckPending.get()) recheck(Trigger.USER, userRecheckProject()); // "Check Again" during this probe
      }
    });
    if (!started) {
      rechecking.set(false);
      userRecheckPending.set(false);
    }
  }

  private static @Nullable Project userRecheckProject() {
    WeakReference<Project> project = userRecheckProject;
    return project != null ? project.get() : null;
  }

  /** Pooled thread. */
  private static void runRecheck(Trigger trigger, @Nullable Project project) {
    HeifBackend backend = HeifBackends.current();
    HeifBackendStatus before = backend.cachedStatus();
    HeifBackendStatus after = backend.recheckStatus();
    log("HEIC decoder checked again (" + trigger + "): ", after);
    if (shutDown) return;
    if (after.isAvailable()) {
      activationCheckUntil = 0;
      boolean becameAvailable = before == null || !before.isAvailable();
      if (becameAvailable) HeicViews.decoderBecameAvailable();
      if (becameAvailable || trigger == Trigger.USER) DecoderPrompt.showAvailable(project);
    }
    else {
      if (!after.equals(before)) HeicViews.updateBanners(); // e.g. the HEIF extension is there now, HEVC is still missing
      if (trigger == Trigger.USER) DecoderPrompt.showStillMissing(after, project);
    }
  }

  /**
   * The user clicked a remedy action that may make them install something outside the IDE (a Store page, a copied
   * command): for the next {@link #ACTIVATION_CHECK_MILLIS}, activating the IDE again re-checks the decoder.
   */
  static void remedyActionPerformed() {
    if (!shutDown) activationCheckUntil = System.currentTimeMillis() + ACTIVATION_CHECK_MILLIS;
  }

  /** An IDE window was activated (EDT). Free unless a remedy action was performed; debounced. */
  static void applicationActivated() {
    long until = activationCheckUntil;
    if (until == 0) return;
    long now = System.currentTimeMillis();
    if (now > until) {
      activationCheckUntil = 0;
      return;
    }
    if (now - lastActivationCheck < ACTIVATION_DEBOUNCE_MILLIS) return;
    lastActivationCheck = now;
    recheck(Trigger.ACTIVATION, null);
  }

  /** Whether activating the IDE currently re-checks the decoder. */
  static boolean isActivationCheckArmed() {
    long until = activationCheckUntil;
    return until != 0 && System.currentTimeMillis() <= until;
  }

  /** Before the plugin is unloaded: no more probes, re-checks or activation checks. */
  static void shutDown() {
    shutDown = true;
    activationCheckUntil = 0;
    userRecheckPending.set(false);
    userRecheckProject = null;
  }

  /** Tests: forgets the per-session state (the backend is replaced through {@code HeifBackends.replaceForTests}). */
  @TestOnly
  static void resetForTests() {
    synchronized (LOCK) {
      firstProbe = null;
    }
    startupCheck.set(false);
    activationCheckUntil = 0;
    lastActivationCheck = 0;
    userRecheckPending.set(false);
    userRecheckProject = null;
    shutDown = false;
  }

  @TestOnly
  static void resetActivationDebounceForTests() {
    lastActivationCheck = 0;
  }

  @TestOnly
  static boolean isRecheckingForTests() {
    return rechecking.get();
  }

  /** Runs {@code task} on the application pool; {@code false} if it was not accepted (shut down). */
  private static boolean execute(Runnable task) {
    if (shutDown) return false;
    try {
      AppExecutorUtil.getAppExecutorService().execute(() -> {
        try {
          task.run();
        }
        catch (RuntimeException | LinkageError e) {
          LOG.warn("HEIC decoder check failed", e);
        }
      });
      return true;
    }
    catch (RejectedExecutionException e) {
      return false;
    }
  }

  private static void log(String prefix, HeifBackendStatus status) {
    if (status.reason() == HeifBackendStatus.Reason.ERROR) LOG.warn(prefix + status);
    else LOG.info(prefix + (status.isAvailable() ? status.detail() : status.toString()));
  }
}
