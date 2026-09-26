package cn.yooss.heic.linux;

import cn.yooss.heic.HeicSettings;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Linux backend: the system's libheif ({@code libheif.so.1}) with an HEVC decoder (the libde265 plugin, or libde265
 * linked in), loaded with {@code dlopen} through the IDE's JNA ({@link Libheif}). Nothing is bundled.
 * <p>
 * <b>Probe</b> (cached, runs on a pooled thread; also on "Check Again"):
 * <ol>
 *   <li>Linux on x86-64 or AArch64 only (the {@code heif_error} calling convention, see {@link Libheif}), else
 *   {@code UNSUPPORTED_OS};</li>
 *   <li>load libheif: the path configured in <i>Advanced Settings</i> ({@link HeicSettings#LIBHEIF_PATH}) if set,
 *   otherwise {@code libheif.so.1} and {@code heif} through the dynamic linker's search, then (NixOS) the system and
 *   user profiles; only a libheif 1.x is accepted (the ABI of {@code libheif.so.1}). If none loads:
 *   {@code LINUX_LIBHEIF_MISSING} with the attempts and errors in the detail. Once a libheif is loaded in the IDE
 *   process, no other libheif file is opened (see below);</li>
 *   <li>{@code heif_init(NULL)} once (libheif 1.13+; it loads the codec plugins in 1.14+);</li>
 *   <li>{@code heif_have_decoder_for_format(heif_compression_HEVC)}. If there is none, the plugin directories are
 *   scanned again ({@code heif_load_plugins}, libheif 1.14+), so that "Check Again" finds a plugin installed after the
 *   IDE started; still none: {@code LINUX_HEVC_PLUGIN_MISSING} (with the plugin directories in the detail);</li>
 *   <li>otherwise available.</li>
 * </ol>
 * Both missing states carry the distribution's install command ({@link LibheifRemedy}, from {@code /etc/os-release})
 * and a link to the README section about Linux.
 * <p>
 * <b>Initialization and unloading.</b> libheif's {@code heif_init}/{@code heif_deinit} are reference counted and
 * process-wide. The backend calls {@code heif_init} once per loaded library and never {@code heif_deinit}:
 * deinitializing unloads the codec plugins and frees libheif's global tables, which would break other users of
 * libheif in the IDE process (and a decode still running on another thread while the plugin is unloaded). The cost is
 * one reference count per plugin load; the plugins stay loaded until the process ends (so after a reinstall of this
 * plugin, {@code dlopen} returns the same, still initialized library). {@link #dispose()} only drops the references to
 * JNA's objects.
 * <p>
 * <b>One libheif per process.</b> A loaded libheif can never really be unloaded (it is initialized, its plugins depend
 * on it, and JNA may keep it), and JNA opens libraries with {@code RTLD_GLOBAL}. A second, different
 * {@code libheif.so.1} opened next to it (the setting changed to another libheif, then "Check Again", or the plugin
 * reloaded after that) would bind its plugins and its own exported functions to the first one, which mixes the objects
 * of two versions and can crash the IDE. So once a libheif is mapped in the process ({@code /proc/self/maps}), a
 * configured path that is another file is not opened: the loaded libheif stays in use, and the detail says that the
 * configured one is used after an IDE restart. (Opening the same file, or a name without a slash, returns the loaded
 * library.)
 */
public final class LibheifHeifBackend extends AbstractHeifBackend {
  private static final String[] SYSTEM_LIBRARY_NAMES = {"libheif.so.1", "heif"};

  private final Supplier<List<String>> candidates;
  private final Supplier<LinuxDistribution> distribution;
  private final boolean requireLinux;
  private final Function<String, Libheif> opener;
  private final Supplier<Path> mappedLibheif;

  // Guarded by the status lock of AbstractHeifBackend (probe); read by the decoding threads.
  private volatile @Nullable Libheif library;
  private volatile @Nullable LibheifDecoder decoder;
  private @Nullable List<String> libraryCandidates;
  private @Nullable String initError;

  /** The backend of the plugin: system libheif or the configured path, on Linux. */
  public LibheifHeifBackend() {
    this(() -> libraryCandidates(HeicSettings.libheifPath(), LinuxDistribution.current()), LinuxDistribution::current,
         true);
  }

  /**
   * @param candidates   libraries to try, in order (see {@link #libraryCandidates})
   * @param requireLinux {@code false} in tests that load a libheif on another OS (e.g. Homebrew's on macOS)
   */
  LibheifHeifBackend(@NotNull Supplier<List<String>> candidates, @NotNull Supplier<LinuxDistribution> distribution,
                     boolean requireLinux) {
    this(candidates, distribution, requireLinux, Libheif::open, LibheifHeifBackend::mappedLibheif);
  }

  /**
   * @param opener        opens a library ({@link Libheif#open}; tests record the calls)
   * @param mappedLibheif the libheif file already mapped in the process, or {@code null} ({@link #mappedLibheif()})
   */
  LibheifHeifBackend(@NotNull Supplier<List<String>> candidates, @NotNull Supplier<LinuxDistribution> distribution,
                     boolean requireLinux, @NotNull Function<String, Libheif> opener, @NotNull Supplier<Path> mappedLibheif) {
    this.candidates = candidates;
    this.distribution = distribution;
    this.requireLinux = requireLinux;
    this.opener = opener;
    this.mappedLibheif = mappedLibheif;
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
    String note = LibheifRemedy.note(distro);

    List<String> tried = candidates.get();
    Libheif lib = library;
    String restartNote = null;
    if (lib == null || !tried.equals(libraryCandidates)) {
      // Never a second, different libheif next to one that is loaded already (see the class comment).
      Path mapped = mappedLibheif.get();
      List<String> failures = new ArrayList<>();
      List<String> openable = new ArrayList<>();
      for (String candidate : tried) {
        if (mapped != null && candidate.indexOf('/') >= 0 && !sameFile(mapped, candidate)) {
          failures.add(candidate + " (not loaded: " + mapped + " is loaded in this IDE process already)");
        }
        else {
          openable.add(candidate);
        }
      }
      boolean deferred = openable.isEmpty() && mapped != null;
      if (deferred) {
        // Every candidate is another libheif: keep using the loaded one (dlopen of its own file returns it).
        restartNote = String.join(", ", tried) + " is used after an IDE restart: " + mapped + " is loaded in this IDE "
                      + "process already, and a second libheif next to it could crash the IDE";
        openable.add(mapped.toString());
      }
      Libheif current = lib;
      lib = null;
      for (String candidate : openable) {
        if (deferred && current != null) {
          lib = current; // the loaded library itself: no second heif_init
          break;
        }
        try {
          Libheif opened = opener.apply(candidate);
          if (opened.majorVersion() == 1) {
            lib = opened;
            break;
          }
          // e.g. a future libheif.so.2 found by the short name "heif": its ABI is unknown, so it is never called
          failures.add(candidate + " (libheif " + opened.version() + " is not supported, 1.x is needed)");
        }
        catch (UnsatisfiedLinkError e) {
          failures.add(candidate + " (" + condense(e.getMessage()) + ")");
        }
      }
      if (lib == null) {
        decoder = null;
        library = null;
        return missing(Reason.LINUX_LIBHEIF_MISSING, distro,
                       "Cannot load libheif on " + distro.prettyName() + ", tried: " + String.join("; ", failures)
                       + (note != null ? ". " + note : ""));
      }
      if (lib != current) {
        initError = null;
        try {
          lib.init();
        }
        catch (LibheifException e) {
          initError = e.getMessage(); // e.g. a plugin that cannot be loaded; the built-in codecs still work
        }
      }
      library = lib;
      // Deferred: not remembered as loaded from the configured candidates, so every probe says it again.
      libraryCandidates = deferred ? null : tried;
    }

    String description = describe(lib) + (restartNote != null ? " (" + restartNote + ")" : "");
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

  /**
   * The libraries to try: the configured path ({@code ~} expanded; for a directory, {@code libheif.so.1} and
   * {@code libheif.so} in it) and nothing else, or else the system's {@code libheif.so.1} / {@code heif} followed, on
   * NixOS, by the profiles' {@code lib} directories that contain a {@code libheif.so.1}.
   */
  static @NotNull List<String> libraryCandidates(@NotNull String configuredPath, @NotNull LinuxDistribution distro) {
    String home = System.getProperty("user.home", "");
    String configured = configuredPath.trim();
    if (!configured.isEmpty()) {
      if (configured.equals("~")) configured = home;
      else if (configured.startsWith("~/")) configured = home + configured.substring(1);
      File file = new File(configured);
      if (file.isDirectory()) {
        return List.of(new File(file, "libheif.so.1").getPath(), new File(file, "libheif.so").getPath());
      }
      return List.of(configured);
    }
    Set<String> result = new LinkedHashSet<>(Arrays.asList(SYSTEM_LIBRARY_NAMES));
    if (distro.id().equals("nixos") || new File("/etc/NIXOS").exists()) {
      String user = System.getProperty("user.name", "");
      for (String directory : new String[]{"/run/current-system/sw/lib", home + "/.nix-profile/lib",
                                           "/etc/profiles/per-user/" + user + "/lib"}) {
        File library = new File(directory, "libheif.so.1");
        if (library.exists()) result.add(library.getPath());
      }
    }
    return new ArrayList<>(result);
  }

  /** E.g. {@code "libheif 1.17.6 (/usr/lib/x86_64-linux-gnu/libheif.so.1.17.6)"}. */
  private String describe(Libheif lib) {
    Path file = mappedLibheif.get();
    return "libheif " + lib.version() + " (" + (file != null ? file : lib.source) + ")";
  }

  /** Whether {@code candidate} is the file {@code mapped} (through symbolic links); {@code false} if unknown. */
  static boolean sameFile(@NotNull Path mapped, @NotNull String candidate) {
    try {
      return Files.isSameFile(mapped, Paths.get(candidate));
    }
    catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /**
   * The libheif file mapped in this process ({@code /proc/self/maps}): loaded by this plugin, an earlier instance of
   * it, or anything else in the IDE; {@code null} if there is none or this is not Linux.
   */
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
