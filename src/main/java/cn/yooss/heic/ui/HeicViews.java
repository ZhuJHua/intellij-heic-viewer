package cn.yooss.heic.ui;

import cn.yooss.heic.Downscales;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that shows HEIC images or the decoder's status, refreshed when the status changes: the editor banners
 * ({@link HeicDecoderNotificationProvider}, {@link HeicDownscaleNotificationProvider}) and open HEIC image editors.
 */
final class HeicViews {
  private static final Logger LOG = Logger.getInstance(HeicViews.class);
  /** Reasons whose banner the user closed in this session (guarded by itself). */
  private static final Set<HeifBackendStatus.Reason> hiddenBanners = EnumSet.noneOf(HeifBackendStatus.Reason.class);
  /** Files (paths) whose "shown smaller" banner the user closed in this session (guarded by itself). */
  private static final Set<String> hiddenDownscaleBanners = new HashSet<>();
  private static volatile boolean shutDown;

  private HeicViews() {
  }

  /** After the reader was registered: the banners follow the images the heap safety valve decodes smaller. */
  static void start() {
    if (!shutDown) Downscales.setListener(HeicViews::downscalesChanged);
  }

  /**
   * An image was decoded smaller than it is, or at full size again ({@link Downscales}; on the decoding thread): the
   * banners are collected again (asynchronously).
   */
  static void downscalesChanged() {
    updateBanners();
  }

  /** Whether the "shown smaller" banner of the file with this path was closed (or the plugin is being unloaded). */
  static boolean isDownscaleBannerHidden(@NotNull String key) {
    if (shutDown) return true;
    synchronized (hiddenDownscaleBanners) {
      return hiddenDownscaleBanners.contains(key);
    }
  }

  /** The user closed the "shown smaller" banner of a file (its path): hidden until the IDE restarts. */
  static void hideDownscaleBanner(@NotNull String key) {
    synchronized (hiddenDownscaleBanners) {
      hiddenDownscaleBanners.add(key);
    }
    updateBanners();
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
   * EDT. Makes the open HEIC image editors load their image again. The image editor loads a file only when it is opened
   * or changes ({@code IfsUtil} caches no failure, but nothing asks it again), so each editor is refreshed like after a
   * change on disk ({@code ImageEditorImpl.refreshFile()}, public); for any other image editor implementation the files
   * are re-parsed ({@code FileContentUtilCore.reparseFiles}, which the image editor also reacts to). Diff windows that
   * are open keep showing "Image not loaded" until they are opened again.
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
   * Before the plugin is unloaded (EDT): no more banners or refreshes, and the banners on screen are removed. The
   * platform does not do it reliably: IntelliJ 2026.1 only re-collects the banners of the providers that are still
   * registered, so the panel of this plugin would stay in the editor (with its actions, plugin classes) and keep the
   * plugin class loader alive. {@link #removePanels} removes a provider's panels from the editors of all open files right
   * away; after {@code shutDown} the provider collects nothing, and a function it returned earlier creates no panel when
   * the platform applies it later on the EDT ({@link #isBannerHidden} is checked again then), so no new panel appears.
   */
  static void shutDown() {
    shutDown = true;
    Downscales.setListener(null);
    Downscales.clear();
    Application application = ApplicationManager.getApplication();
    if (application == null || !application.isDispatchThread()) return;
    // Their classes identify the panels.
    List<EditorNotificationProvider> providers =
      List.of(new HeicDecoderNotificationProvider(), new HeicDownscaleNotificationProvider());
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      if (project.isDisposed()) continue;
      for (EditorNotificationProvider provider : providers) {
        try {
          removePanels(EditorNotifications.getInstance(project), provider);
        }
        catch (RuntimeException | LinkageError | ReflectiveOperationException e) {
          LOG.warn("Cannot remove the HEIC editor banners before unloading", e);
        }
      }
      restartBannerUpdates(project);
    }
  }

  /**
   * Before the plugin is unloaded (EDT): replaces the banner updates in flight for the open files of {@code project}
   * with new ones. An update the platform started earlier holds the list of all banner providers it began with, this
   * plugin's classes included, and needs the EDT between two providers. The IDE checks on the EDT whether the plugin
   * class loader can be collected, so such an update cannot finish in time and the unload fails ("class loader cannot be
   * unloaded"; reproduced on IntelliJ IDEA 2024.1.7 right after a diff was opened: the memory snapshot shows
   * {@code EditorNotificationsImpl}'s update job -> {@code ExtensionComponentAdapter[]} -> a provider class of this
   * plugin). {@code updateNotifications(file)} cancels the file's current update, and the platform drains the EDT queue
   * right after {@code beforePluginUnload}, so the cancelled updates end there. The new ones wait 100 ms before they list
   * the providers, normally until this plugin's have been removed.
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
   * {@code EditorNotifications.removeNotificationsForProvider(provider)}: removes the provider's panels from the editors
   * right away (IntelliJ 2025.x and newer). 2024.1 and 2024.2 have the same operation only under its former name,
   * {@code updateNotifications(provider)}, which the newer platforms deprecate (and delegate to). Called by name, so that
   * the plugin links neither a method that 2024.1 lacks nor a deprecated one.
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
    synchronized (hiddenDownscaleBanners) {
      hiddenDownscaleBanners.clear();
    }
    shutDown = false;
  }
}
