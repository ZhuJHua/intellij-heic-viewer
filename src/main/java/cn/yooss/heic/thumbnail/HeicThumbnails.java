package cn.yooss.heic.thumbnail;

import cn.yooss.heic.HeicImageReaderSpi;
import cn.yooss.heic.HeicSettings;
import cn.yooss.heic.HeifSniffer;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackends;
import com.intellij.ide.ui.VirtualFileAppearanceListener;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBImageIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thumbnail icons for HEIC files (used by {@link HeicThumbnailIconProvider}).
 * <p>
 * <b>Never decodes on the calling thread.</b> {@link #getIcon} only looks up an LRU cache; on a miss it queues the
 * decode on a bounded pool executor (2 threads) and returns {@code null}, so the platform shows the file type's
 * Image icon. When the thumbnail is ready, {@link VirtualFileAppearanceListener#fireVirtualFileAppearanceChanged}
 * is published on the EDT for the file: the platform clears its icon deferrer cache, the project view updates the
 * file's node ({@code ProjectFileNodeUpdater}), the navigation bar and the editor tabs update their presentation, and
 * the provider is asked again and now returns the cached icon.
 * <p>
 * <b>Dynamic unload.</b> Everything the platform can retain is made of platform/JDK classes only: the icon is a
 * {@link JBImageIcon} over a {@link java.awt.image.BaseMultiResolutionImage} of {@link BufferedImage}s, and no
 * evaluator/lambda of this plugin is handed to the platform's deferred-icon machinery. The executor, the caches and
 * queued EDT runnables (expired via their {@code expired} condition, purged by {@code DynamicPlugins}) are released
 * in {@link #shutDown()}, called from {@code HeicDynamicPluginListener.beforePluginUnload}.
 */
public final class HeicThumbnails {
  private static final Logger LOG = Logger.getInstance(HeicThumbnails.class);

  /** Larger files keep the default icon. */
  public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
  /** Logical icon size (before the IDE's UI scale), the size of all file icons. */
  static final int ICON_SIZE = 16;
  static final int MAX_CACHED_ICONS = 500;
  /** Files whose icon was asked for, refreshed when the setting is toggled. */
  static final int MAX_REMEMBERED_FILES = 1000;
  static final int MAX_THREADS = 2;
  /** How long {@link #shutDown()} waits for running decodes before the plugin is unloaded. */
  static final long UNLOAD_WAIT_MILLIS = 1000;

  private static final Object LOCK = new Object();
  private static volatile HeicThumbnails instance;
  /** Set once by {@link #shutDown()} (before the plugin is unloaded); a reloaded plugin has a new class loader. */
  private static volatile boolean shutDown;

  private final ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("HEIC Thumbnails", MAX_THREADS);
  private final ThumbnailLoader<ThumbnailKey, Icon> loader =
      new ThumbnailLoader<>(executor, MAX_CACHED_ICONS, (key, error) -> LOG.debug("No thumbnail for " + key.url(), error));
  private final LruCache<String, VirtualFile> requestedFiles = new LruCache<>(MAX_REMEMBERED_FILES);
  private final Set<VirtualFile> filesToRefresh = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean refreshQueued = new AtomicBoolean();
  private volatile boolean disposed;

  private HeicThumbnails() {
  }

  /**
   * The thumbnail icon of {@code file}, or {@code null} for the default icon: not a local HEIC file, too large,
   * thumbnails disabled, not decoded yet (a decode is queued) or not decodable. Cheap and non-blocking; any thread.
   */
  static @Nullable Icon getIcon(@NotNull VirtualFile file) {
    if (!isCandidate(file)) return null;
    HeicThumbnails thumbnails = getInstance();
    return thumbnails == null ? null : thumbnails.iconFor(file);
  }

  /** Local, non-empty HEIC/HEIF files of at most {@link #MAX_FILE_BYTES} (VFS attributes only, no disk access). */
  static boolean isCandidate(@NotNull VirtualFile file) {
    if (file.isDirectory() || !hasThumbnailExtension(file.getExtension()) || !file.isInLocalFileSystem()) return false;
    long length = file.getLength();
    return length > 0 && length <= MAX_FILE_BYTES;
  }

  static boolean hasThumbnailExtension(@Nullable String extension) {
    if (extension == null) return false;
    for (String suffix : HeicImageReaderSpi.SUFFIXES) {
      if (suffix.equalsIgnoreCase(extension)) return true;
    }
    return false;
  }

  private static @Nullable HeicThumbnails getInstance() {
    HeicThumbnails result = instance;
    if (result != null || shutDown) return result;
    synchronized (LOCK) {
      if (instance == null && !shutDown) instance = new HeicThumbnails();
      return instance;
    }
  }

  private @Nullable Icon iconFor(@NotNull VirtualFile file) {
    String url = file.getUrl();
    requestedFiles.put(url, file);
    if (!HeicSettings.projectViewThumbnails()) return null;
    ThumbnailKey key = ThumbnailKey.of(url, file.getTimeStamp(), file.getLength(), JBUI.scale(ICON_SIZE),
                                       ThumbnailGeometry.maxScreenScale());
    return loader.get(key, () -> load(file, key), () -> queueRefresh(List.of(file)));
  }

  /**
   * Runs on the executor. Returns {@code null} (nothing cached) when the work became unnecessary; throws (the failure
   * is cached for this key) when the file cannot be read or decoded.
   */
  private @Nullable Icon load(@NotNull VirtualFile file, @NotNull ThumbnailKey key) throws IOException {
    if (disposed || !file.isValid() || !HeicSettings.projectViewThumbnails()) return null;
    // The first call probes the system decoder (here, off the EDT). Without one the file is not even read; the failure
    // is cached per file until refreshAll() (e.g. after the missing decoder was installed).
    HeifBackend backend = HeifBackends.current();
    HeifBackendStatus status = backend.status();
    if (!status.isAvailable()) throw new IOException(backend.displayName() + " is not available: " + status);
    byte[] data = readHeifFile(file.toNioPath());
    if (disposed) return null; // the plugin is being unloaded: skip the native decode
    BufferedImage decoded = backend.decodeThumbnail(data, key.decodeSize());
    Image image = ThumbnailRenderer.renderIcon(decoded, key.iconSize(), key.scales());
    return new JBImageIcon(image);
  }

  /**
   * Reads the file directly (not through the VFS, whose content cache must not fill up with photos), but only if its
   * header is a HEIC/HEIF {@code ftyp} box: system decoders (ImageIO.framework, WIC) pick the codec by content, and a
   * file that is merely named {@code *.heic} (really a PSD, TIFF, TGA ...) must not reach other native codecs just
   * because its folder is shown. The image viewer applies the same check ({@link HeicImageReaderSpi#canDecodeInput}). Anything else is read
   * no further than the header and fails with an {@link IOException}.
   */
  static byte[] readHeifFile(Path path) throws IOException {
    long size = Files.size(path);
    if (size <= 0 || size > MAX_FILE_BYTES) throw new IOException("Unsupported file size " + size + ": " + path);
    byte[] data = new byte[(int) size];
    try (InputStream in = Files.newInputStream(path)) {
      int n = in.readNBytes(data, 0, Math.min(8, data.length));
      int needed = Math.min(HeifSniffer.headerBytesNeeded(data, n), data.length);
      if (needed > n) n += in.readNBytes(data, n, needed - n);
      if (!HeifSniffer.isHeif(data, n)) throw new IOException("Not a HEIC/HEIF file: " + path);
      n += in.readNBytes(data, n, data.length - n);
      return n == data.length ? data : Arrays.copyOf(data, n); // the file shrank in the meantime
    }
  }

  /** Publishes appearance changes for {@code files} on the EDT (coalesced: at most one queued runnable). */
  private void queueRefresh(Collection<VirtualFile> files) {
    if (disposed || files.isEmpty()) return;
    filesToRefresh.addAll(files);
    if (!refreshQueued.compareAndSet(false, true)) return;
    Application application = ApplicationManager.getApplication();
    if (application == null || application.isDisposed()) {
      refreshQueued.set(false);
      return;
    }
    application.invokeLater(this::refreshQueuedFiles, ModalityState.nonModal(), ignored -> disposed || application.isDisposed());
  }

  private void refreshQueuedFiles() {
    refreshQueued.set(false);
    List<VirtualFile> files = new ArrayList<>(filesToRefresh);
    filesToRefresh.removeAll(files);
    fireAppearanceChanged(files);
  }

  /**
   * EDT. The appearance event refreshes the project view, the navigation bar and the <em>selected</em> editor tab
   * ({@code FileEditorVirtualFileAppearanceListener} only updates the current file), so the other editor tabs showing
   * the file are updated explicitly.
   */
  private static void fireAppearanceChanged(Collection<VirtualFile> files) {
    Project[] projects = ProjectManager.getInstance().getOpenProjects();
    for (VirtualFile file : files) {
      if (!file.isValid()) continue;
      VirtualFileAppearanceListener.fireVirtualFileAppearanceChanged(file);
      for (Project project : projects) {
        if (project.isDisposed()) continue;
        FileEditorManager editors = FileEditorManager.getInstance(project);
        if (editors.isFileOpen(file)) editors.updateFilePresentation(file);
      }
    }
  }

  /**
   * The thumbnails setting was toggled: drop all icons and refresh every file whose icon was asked for, so that
   * the project view, editor tabs and navigation bar switch between thumbnails and the default icon right away.
   */
  static void settingChanged() {
    refreshAll();
  }

  /**
   * Drops all icons and cached failures and refreshes every file whose icon was asked for: after the thumbnails
   * setting was toggled, or after the system decoder became available (see {@code HeicDecoderAvailability}).
   */
  public static void refreshAll() {
    HeicThumbnails thumbnails = instance;
    if (thumbnails == null || thumbnails.disposed) return;
    thumbnails.loader.clear();
    thumbnails.queueRefresh(thumbnails.requestedFiles.values());
  }

  /**
   * Files changed on disk: refresh the ones currently shown with a thumbnail (their cache key changes with the
   * timestamp/length, so the provider decodes them again). Called from a VFS listener on the EDT; only compares
   * strings, never touches the disk.
   */
  static void filesChanged(@NotNull List<? extends VFileEvent> events) {
    HeicThumbnails thumbnails = instance;
    if (thumbnails == null || thumbnails.disposed) return;
    List<VirtualFile> changed = null;
    for (VFileEvent event : events) {
      if (!(event instanceof VFileContentChangeEvent contentChange)) continue;
      VirtualFile file = contentChange.getFile();
      if (!hasThumbnailExtension(file.getExtension()) || !thumbnails.requestedFiles.containsKey(file.getUrl())) continue;
      if (changed == null) changed = new ArrayList<>();
      changed.add(file);
    }
    if (changed != null) thumbnails.queueRefresh(changed);
  }

  /**
   * Releases everything before the plugin is unloaded: cancels queued decodes, shuts the executor down and waits up
   * to {@link #UNLOAD_WAIT_MILLIS} for running ones, clears the caches and expires queued EDT runnables. Idempotent;
   * afterwards {@link #getIcon} always returns {@code null}.
   */
  public static void shutDown() {
    HeicThumbnails thumbnails;
    synchronized (LOCK) {
      shutDown = true;
      thumbnails = instance;
      instance = null;
    }
    if (thumbnails != null) thumbnails.dispose();
  }

  private void dispose() {
    disposed = true;
    loader.shutDown();
    executor.shutdownNow(); // drops queued decodes
    requestedFiles.clear();
    filesToRefresh.clear();
    try {
      // Running decodes keep this plugin's classes on their stacks, and the platform checks right after
      // beforePluginUnload whether the class loader can be collected (on JBR: one full GC, no retry). The decode path
      // needs neither the EDT nor a lock, so this bounded wait cannot deadlock.
      if (!executor.awaitTermination(UNLOAD_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
        LOG.info("HEIC thumbnail decodes still running at unload; a restart may be required");
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    catch (RuntimeException e) {
      LOG.info("Cannot wait for HEIC thumbnail decodes at unload", e);
    }
  }
}
