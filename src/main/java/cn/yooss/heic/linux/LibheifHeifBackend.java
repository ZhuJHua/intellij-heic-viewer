package cn.yooss.heic.linux;

import cn.yooss.heic.backend.AbstractHeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.jna.JnaLibraries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Linux backend: the system's libheif ({@code libheif.so.1}) with an HEVC decoder (the libde265 plugin, or libde265
 * linked in), loaded with {@code dlopen} through the IDE's JNA ({@link Libheif}). Nothing is bundled.
 * <p>
 * <b>Probe</b> (cached, runs on a pooled thread; also on "Check Again"):
 * <ol>
 *   <li>Linux on x86-64 or AArch64 only (the {@code heif_error} calling convention, see {@link Libheif}), else
 *   {@code UNSUPPORTED_OS};</li>
 *   <li>load libheif by its standard names ({@link #SYSTEM_LIBRARY_NAMES}) through the dynamic linker's search; only a
 *   libheif 1.x is accepted (the ABI of {@code libheif.so.1}). If none loads: {@code LINUX_LIBHEIF_MISSING} with the
 *   attempts and errors in the detail;</li>
 *   <li>{@code heif_init(NULL)} once (libheif 1.13+; it loads the codec plugins in 1.14+);</li>
 *   <li>{@code heif_have_decoder_for_format(heif_compression_HEVC)}. If there is none, the plugin directories are
 *   scanned again ({@code heif_load_plugins}, libheif 1.14+), so that "Check Again" finds a plugin installed after the
 *   IDE started; still none: {@code LINUX_HEVC_PLUGIN_MISSING} (with the plugin directories in the detail);</li>
 *   <li>otherwise available.</li>
 * </ol>
 * Both missing states carry the distribution's install command ({@link LibheifRemedy}, from {@code /etc/os-release})
 * and a link to the README section about Linux.
 * <p>
 * {@code heif_init} is called once per loaded library and {@code heif_deinit} never: it is process-wide and would unload
 * the codec plugins for every user of libheif in the IDE, including a decode still running. {@link #dispose()} only
 * drops the references to JNA's objects.
 */
public final class LibheifHeifBackend extends AbstractHeifBackend {
  /** The names libheif is loaded by: its soname, then JNA's short name (JNA also searches {@code libheif.so*}). */
  static final List<String> SYSTEM_LIBRARY_NAMES = List.of("libheif.so.1", "heif");

  private final List<String> candidates;
  private final Supplier<LinuxDistribution> distribution;
  private final boolean requireLinux;

  // Guarded by the status lock of AbstractHeifBackend (probe); read by the decoding threads.
  private volatile @Nullable Libheif library;
  private volatile @Nullable LibheifDecoder decoder;
  private @Nullable String initError;

  /** The backend of the plugin: the system's libheif, on Linux. */
  public LibheifHeifBackend() {
    this(SYSTEM_LIBRARY_NAMES, LinuxDistribution::current, true);
  }

  /**
   * @param candidates   libraries to try, in order
   * @param requireLinux {@code false} in tests that load a libheif on another OS (e.g. Homebrew's on macOS)
   */
  LibheifHeifBackend(@NotNull List<String> candidates, @NotNull Supplier<LinuxDistribution> distribution, boolean requireLinux) {
    this.candidates = List.copyOf(candidates);
    this.distribution = distribution;
    this.requireLinux = requireLinux;
  }

  @Override
  public @NotNull String id() {
    return "linux-libheif";
  }

  @Override
  public @NotNull String displayName() {
    return "libheif";
  }

  @Override
  protected @NotNull HeifBackendStatus probe() {
    String os = System.getProperty("os.name", "");
    String arch = System.getProperty("os.arch", "");
    if (requireLinux && HeifBackends.Os.current() != HeifBackends.Os.LINUX) {
      return HeifBackendStatus.unavailable(Reason.UNSUPPORTED_OS, "Not Linux: " + os);
    }
    if (!Libheif.isSupportedPlatform(arch)) {
      return HeifBackendStatus.unavailable(Reason.UNSUPPORTED_OS, "libheif is only called on x86-64 and AArch64 (its "
                                                                  + "error results are read from registers), not on " + arch);
    }
    if (!JnaLibraries.isPresent()) {
      return HeifBackendStatus.unavailable(Reason.ERROR, "The IDE does not provide JNA (com.sun.jna)");
    }
    LinuxDistribution distro = distribution.get();

    Libheif lib = library;
    if (lib == null) {
      List<String> failures = new ArrayList<>();
      for (String candidate : candidates) {
        try {
          Libheif opened = Libheif.open(candidate);
          if (opened.majorVersion() == 1) {
            lib = opened;
            break;
          }
          // e.g. a libheif.so.2 found by the short name "heif": its ABI is unknown, so it is never called
          failures.add(candidate + " (libheif " + opened.version() + " is not supported, 1.x is needed)");
        }
        catch (UnsatisfiedLinkError e) {
          failures.add(candidate + " (" + condense(e.getMessage()) + ")");
        }
      }
      if (lib == null) {
        decoder = null;
        String note = LibheifRemedy.note(distro);
        return missing(Reason.LINUX_LIBHEIF_MISSING, distro,
                       "Cannot load libheif on " + distro.prettyName() + ", tried: " + String.join("; ", failures)
                       + (note != null ? ". " + note : ""));
      }
      initError = null;
      try {
        lib.init();
      }
      catch (LibheifException e) {
        initError = e.getMessage(); // e.g. a plugin that cannot be loaded; the built-in codecs still work
      }
      library = lib;
    }

    String description = describe(lib);
    boolean hevc = lib.hasHevcDecoder();
    if (!hevc && lib.canLoadPlugins()) {
      // "Check Again" after installing a plugin package: heif_init loaded the plugins only once.
      for (String directory : pluginDirectories(lib)) {
        try {
          lib.loadPlugins(directory);
        }
        catch (LibheifException e) {
          // reported below as a missing decoder
        }
      }
      hevc = lib.hasHevcDecoder();
    }
    if (!hevc) {
      decoder = null;
      return missing(Reason.LINUX_HEVC_PLUGIN_MISSING, distro,
                     description + " has no HEVC decoder on " + distro.prettyName() + "; plugin directories: "
                     + describePluginDirectories(lib) + (initError != null ? "; " + initError : ""));
    }
    decoder = new LibheifDecoder(lib);
    List<String> decoders = lib.hevcDecoderNames();
    return HeifBackendStatus.available(
      description + (decoders.isEmpty() ? "" : " with " + String.join(", ", decoders)) + " through the IDE's JNA "
      + JnaLibraries.version() + " (" + distro.prettyName() + ", " + arch + ")");
  }

  private static HeifBackendStatus missing(Reason reason, LinuxDistribution distro, String detail) {
    return HeifBackendStatus.unavailable(reason, detail)
      .withInstallCommand(LibheifRemedy.installCommand(reason, distro))
      .withInstallUrl(LibheifRemedy.HELP_URL);
  }

  @Override
  protected @NotNull HeifImageInfo doReadInfo(byte[] data) throws IOException {
    return decoder().readInfo(data);
  }

  @Override
  protected @NotNull BufferedImage doDecode(byte[] data, int maxPixelSize) throws IOException {
    return decoder().decode(data, maxPixelSize);
  }

  private LibheifDecoder decoder() throws IOException {
    LibheifDecoder current = decoder;
    if (current == null) throw new IOException("libheif is not loaded");
    return current;
  }

  /** The loaded library, for tests. */
  @Nullable Libheif library() {
    return library;
  }

  /** Drops the references to JNA's objects; libheif stays initialized (see the class comment). */
  @Override
  public void dispose() {
    decoder = null;
    library = null;
  }

  /** E.g. {@code "libheif 1.17.6 (/usr/lib/x86_64-linux-gnu/libheif.so.1.17.6)"}. */
  private static String describe(Libheif lib) {
    Path file = mappedLibheif();
    return "libheif " + lib.version() + " (" + (file != null ? file : lib.source) + ")";
  }

  /** The libheif file mapped in this process ({@code /proc/self/maps}); {@code null} if there is none or unknown. */
  static @Nullable Path mappedLibheif() {
    Path maps = Paths.get("/proc/self/maps");
    if (!Files.isReadable(maps)) return null;
    try (BufferedReader reader = Files.newBufferedReader(maps, StandardCharsets.UTF_8)) {
      for (String line; (line = reader.readLine()) != null; ) {
        int slash = line.indexOf('/');
        if (slash < 0) continue;
        String path = line.substring(slash).trim();
        String name = Paths.get(path).getFileName().toString();
        if (name.startsWith("libheif.so")) return Paths.get(path);
      }
    }
    catch (IOException | RuntimeException e) {
      // not available: the source name is reported instead
    }
    return null;
  }

  /**
   * The directories libheif loads codec plugins from: {@code heif_get_plugin_directories} (libheif 1.17+), else
   * {@code $LIBHEIF_PLUGIN_PATH}, else the default {@code <libdir>/libheif/plugins} next to the loaded library.
   */
  static @NotNull List<String> pluginDirectories(Libheif lib) {
    List<String> directories = lib.pluginDirectories();
    if (directories != null) return directories;
    List<String> result = new ArrayList<>();
    String variable = System.getenv("LIBHEIF_PLUGIN_PATH");
    if (variable != null && !variable.trim().isEmpty()) {
      for (String directory : variable.split(":")) {
        if (!directory.isEmpty()) result.add(directory);
      }
      return result;
    }
    Path file = mappedLibheif();
    Path parent = file != null ? file.getParent() : null;
    if (parent != null) result.add(parent.resolve("libheif").resolve("plugins").toString());
    return result;
  }

  /** E.g. {@code "/usr/lib/x86_64-linux-gnu/libheif/plugins [libheif-aomdec.so, libheif-dav1d.so]"}. */
  private static String describePluginDirectories(Libheif lib) {
    List<String> directories = pluginDirectories(lib);
    if (directories.isEmpty()) return "none (libheif " + lib.version() + " without plugin support)";
    List<String> parts = new ArrayList<>();
    for (String directory : directories) {
      String[] files = new File(directory).list((dir, name) -> name.endsWith(".so"));
      if (files == null) {
        parts.add(directory + " (missing)");
      }
      else {
        Arrays.sort(files);
        parts.add(directory + " " + Arrays.toString(files));
      }
    }
    return String.join(", ", parts);
  }

  /** JNA's multi-line message ("Unable to load library ...", then the dlopen errors) on one line, shortened. */
  static String condense(@Nullable String message) {
    if (message == null) return "?";
    String line = message.trim().replaceAll("\\s*\\R\\s*", " ").replaceAll("\\s+", " ");
    return line.length() > 400 ? line.substring(0, 400) + "..." : line;
  }
}
