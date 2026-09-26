package cn.yooss.heic.ui;

import cn.yooss.heic.HeicImageReaderSpi;
import cn.yooss.heic.backend.HeifBackendStatus;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import com.intellij.util.FileContentUtilCore;
import org.intellij.images.editor.ImageEditor;
import org.intellij.images.editor.ImageFileEditor;
import org.intellij.images.editor.impl.ImageEditorImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that shows HEIC images or the decoder's status, refreshed when the status changes: the editor banner
 * ({@link HeicDecoderNotificationProvider}) and open HEIC image editors.
 */
final class HeicViews {
  private static final Logger LOG = Logger.getInstance(HeicViews.class);
  /** Reasons whose banner the user closed in this session (guarded by itself). */
  private static final Set<HeifBackendStatus.Reason> hiddenBanners = EnumSet.noneOf(HeifBackendStatus.Reason.class);
  private static volatile boolean shutDown;

  private HeicViews() {
  }

  /** Asks every open project to collect the editor banners again (any thread; the platform does it asynchronously). */
  static void updateBanners() {
    if (shutDown) return;
    try {
      EditorNotifications.updateAll();
    }
    catch (RuntimeException | LinkageError e) {
      LOG.info("Cannot update the HEIC editor banners", e);
    }
  }

  /** Whether the banner for {@code reason} was closed in this session (or the plugin is being unloaded). */
  static boolean isBannerHidden(@Nullable HeifBackendStatus.Reason reason) {
    if (shutDown) return true;
    synchronized (hiddenBanners) {
      return reason != null && hiddenBanners.contains(reason);
    }
  }

  /** The user closed the banner: hide it in every editor until the IDE restarts (the balloons still explain it). */
  static void hideBanners(@NotNull HeifBackendStatus.Reason reason) {
    synchronized (hiddenBanners) {
      hiddenBanners.add(reason);
    }
    updateBanners();
  }

  /**
   * The decoder was missing and is available now (any thread): banners disappear and open HEIC editors load their image.
   */
  static void decoderBecameAvailable() {
    if (shutDown) return;
    updateBanners();
    Application application = ApplicationManager.getApplication();
    if (application == null || application.isDisposed()) return;
    application.invokeLater(() -> {
      RemedyActions.closePopups(); // the "copied" confirmation and the "More" popup belong to banners that are going away
      reloadOpenEditors();
    }, ModalityState.nonModal(), ignored -> shutDown || application.isDisposed());
  }

  /**
   * EDT. Makes the open HEIC image editors load their image again, like after a change on disk
   * ({@code ImageEditorImpl.refreshFile()}); the files of other image editor implementations are re-parsed. Open diff
   * windows keep showing "Image not loaded" until they are opened again.
   *
   * @return the number of editors (or files) reloaded
   */
  static int reloadOpenEditors() {
    List<VirtualFile> reparse = new ArrayList<>();
    int reloaded = 0;
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      if (project.isDisposed()) continue;
      FileEditorManager manager = FileEditorManager.getInstance(project);
      for (VirtualFile file : manager.getOpenFiles()) {
        if (!file.isValid() || !HeicImageReaderSpi.isHeicExtension(file.getExtension())) continue;
        for (FileEditor editor : manager.getAllEditors(file)) {
          if (!(editor instanceof ImageFileEditor)) continue;
          if (refresh(((ImageFileEditor) editor).getImageEditor())) reloaded++;
          else if (!reparse.contains(file)) reparse.add(file);
        }
      }
    }
    if (!reparse.isEmpty()) {
      FileContentUtilCore.reparseFiles(reparse);
      reloaded += reparse.size();
    }
    if (reloaded > 0) LOG.info("Reloaded " + reloaded + " HEIC editor(s) after the system HEIF decoder became available");
    return reloaded;
  }

  /** EDT. Whether {@code editor} was told to load its file again. */
  static boolean refresh(@Nullable ImageEditor editor) {
    try {
      if (editor instanceof ImageEditorImpl && !editor.isDisposed()) {
        ((ImageEditorImpl) editor).refreshFile();
        return true;
      }
    }
    catch (LinkageError e) {
      LOG.debug("ImageEditorImpl.refreshFile() is not available", e);
    }
    return false;
  }

  /**
   * Before the plugin is unloaded (EDT): no more banners or refreshes, and the banners on screen are removed
   * ({@link #removePanels}), since a panel left in an editor keeps the plugin class loader alive. Afterwards the provider
   * collects nothing, and a function it returned earlier creates no panel ({@link #isBannerHidden}).
   */
  static void shutDown() {
    shutDown = true;
    Application application = ApplicationManager.getApplication();
    if (application == null || !application.isDispatchThread()) return;
    EditorNotificationProvider provider = new HeicDecoderNotificationProvider(); // its class identifies the panels
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      if (project.isDisposed()) continue;
      try {
        removePanels(EditorNotifications.getInstance(project), provider);
      }
      catch (RuntimeException | LinkageError | ReflectiveOperationException e) {
        LOG.warn("Cannot remove the HEIC editor banners before unloading", e);
      }
      restartBannerUpdates(project);
    }
  }

  /**
   * Before the plugin is unloaded (EDT): restarts the banner updates of the open files of {@code project}. An update in
   * flight holds the list of banner providers it began with, this plugin's included; {@code updateNotifications(file)}
   * cancels it and starts a new one.
   */
  private static void restartBannerUpdates(@NotNull Project project) {
    try {
      EditorNotifications notifications = EditorNotifications.getInstance(project);
      for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
        notifications.updateNotifications(file);
      }
    }
    catch (RuntimeException | LinkageError e) {
      LOG.info("Cannot restart the editor banner updates before unloading", e);
    }
  }

  /**
   * Removes the provider's panels from the editors right away:
   * {@code EditorNotifications.removeNotificationsForProvider(provider)}, or {@code updateNotifications(provider)} where
   * that method is missing. Called by name, so that the plugin links neither a missing nor a deprecated method.
   */
  static void removePanels(@NotNull EditorNotifications notifications, @NotNull EditorNotificationProvider provider)
    throws ReflectiveOperationException {
    Method method;
    try {
      method = EditorNotifications.class.getMethod("removeNotificationsForProvider", EditorNotificationProvider.class);
    }
    catch (NoSuchMethodException e) {
      method = EditorNotifications.class.getMethod("updateNotifications", EditorNotificationProvider.class);
    }
    method.invoke(notifications, provider);
  }

  @TestOnly
  static void resetForTests() {
    synchronized (hiddenBanners) {
      hiddenBanners.clear();
    }
    shutDown = false;
  }
}
