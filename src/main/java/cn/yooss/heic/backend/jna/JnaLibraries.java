package cn.yooss.heic.backend.jna;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Opens native libraries through the JNA that every IntelliJ-based IDE bundles (5.14 in 2024.1/2024.2, 5.17 in
 * 2025.2+), in a way that never pins the plugin class loader, so the plugin stays installable, updatable and removable
 * without a restart.
 * <p>
 * <b>Rules for every backend</b> (a violation makes the plugin unloadable; {@code PluginClassLoaderLeakTest} checks
 * it):
 * <ul>
 *   <li>Only JNA's untyped layer: {@link NativeLibrary} from {@link #open}, {@code NativeLibrary.getFunction(name)},
 *   {@code Function.invokeLong/invokeInt/invokeDouble/invokeVoid/invokePointer(Object[])} with arguments of JDK types
 *   ({@code Long}, {@code Integer}, {@code Double}, {@code byte[]}, {@code char[]}, {@code int[]}, {@code long[]}) and
 *   {@link com.sun.jna.Pointer}, plus {@link Native#malloc}/{@link Native#free}. Strings are passed as NUL-terminated
 *   arrays ({@link #utf8z}, {@link #utf16z}): JNA copies {@code String}/{@code WString} arguments into a
 *   {@code Memory}, which registers with JNA's Cleaner and may start its thread from plugin code (see below).</li>
 *   <li>No {@code Library} interface of the plugin ({@code Native.load}), no {@code Structure}, {@code Union},
 *   {@code ByReference}, {@code Callback} or {@code NativeMapped} subclass in the plugin, no {@code Memory}: JNA keeps
 *   static caches keyed by those classes whose values point back at them, and the entries never clear. Structures are
 *   read and written as bytes at known offsets; by-value structs are passed as their scalar members where the ABI
 *   allows it (see {@code cn.yooss.heic.mac.jna.JnaMacApi.RectPassing}).</li>
 *   <li>JNA's own classes and the IDE's {@code com.sun.jna.platform} classes may be used (they belong to the IDE's
 *   class loader), but none of the plugin's classes may be handed to them.</li>
 *   <li>Libraries are opened with {@link #open}, never with {@code NativeLibrary.getInstance(name, options)} options
 *   that contain a class loader.</li>
 * </ul>
 * <b>Why {@link #open} is needed.</b> Every new {@code NativeLibrary} registers with {@code com.sun.jna.internal.Cleaner}.
 * If the Cleaner's thread ("JNA Cleaner") is not running at that moment, JNA starts it from the calling thread: JNA
 * 5.12/5.13 once per process, JNA 5.14+ on demand (it stops after 30 s without registered objects). A thread started
 * from plugin code inherits the calling thread's context class loader and, on Java 17-23, an
 * {@code AccessControlContext} with the {@code ProtectionDomain} of every class on the calling stack, which references
 * the plugin class loader. Either pins the loader for the thread's lifetime. {@link #open} switches the context class
 * loader to JNA's own loader for the call and clears the inherited context of a Cleaner thread that appeared during the
 * call (needs {@code --add-opens java.base/java.lang=ALL-UNNAMED}, which every IDE launcher passes; without it the
 * repair is skipped). Java 24+ no longer has inherited access control contexts.
 */
public final class JnaLibraries {
  private static final String CLEANER_THREAD = "JNA Cleaner";

  private JnaLibraries() {
  }

  /**
   * Opens (or returns JNA's cached instance of) the library {@code nameOrPath}: an absolute path, a file name such as
   * {@code libheif.so.1} (found by the system's library search) or a short name such as {@code heif}.
   *
   * @throws UnsatisfiedLinkError if the library cannot be loaded
   */
  public static @NotNull NativeLibrary open(@NotNull String nameOrPath) {
    return openAll(nameOrPath)[0];
  }

  /** Opens several libraries (see {@link #open}); all of them must load. */
  public static NativeLibrary[] openAll(String... namesOrPaths) {
    Set<Thread> before = cleanerThreads();
    Thread current = Thread.currentThread();
    ClassLoader contextLoader = current.getContextClassLoader();
    current.setContextClassLoader(NativeLibrary.class.getClassLoader());
    try {
      NativeLibrary[] libraries = new NativeLibrary[namesOrPaths.length];
      for (int i = 0; i < namesOrPaths.length; i++) libraries[i] = NativeLibrary.getInstance(namesOrPaths[i]);
      return libraries;
    }
    finally {
      current.setContextClassLoader(contextLoader);
      for (Thread started : cleanerThreads()) {
        if (!before.contains(started)) detach(started);
      }
    }
  }

  /** {@code value} as a NUL-terminated UTF-8 {@code const char*} argument. */
  public static byte @NotNull [] utf8z(@NotNull String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return Arrays.copyOf(bytes, bytes.length + 1);
  }

  /** {@code value} as a NUL-terminated UTF-16 {@code const wchar_t*} argument (Windows {@code LPCWSTR}). */
  public static char @NotNull [] utf16z(@NotNull String value) {
    return Arrays.copyOf(value.toCharArray(), value.length() + 1);
  }

  /**
   * Version of the JNA the IDE provides at runtime, e.g. {@code 5.17.0} ({@code Native.VERSION} is a compile-time
   * constant, so it is read reflectively), or {@code "?"}.
   */
  public static @NotNull String version() {
    try {
      Field field = Native.class.getField("VERSION"); // declared in the package-private interface Version
      field.setAccessible(true);
      return String.valueOf(field.get(null));
    }
    catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      return "?";
    }
  }

  /** Whether JNA's classes are available (never initializes JNA, so no native code is loaded). */
  public static boolean isPresent() {
    try {
      Class.forName("com.sun.jna.NativeLibrary", false, JnaLibraries.class.getClassLoader());
      return true;
    }
    catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  private static Set<Thread> cleanerThreads() {
    Set<Thread> result = Collections.newSetFromMap(new IdentityHashMap<>());
    ThreadGroup root = Thread.currentThread().getThreadGroup();
    while (root.getParent() != null) root = root.getParent();
    Thread[] threads = new Thread[root.activeCount() + 16];
    int n = root.enumerate(threads, true);
    for (int i = 0; i < n; i++) {
      if (CLEANER_THREAD.equals(threads[i].getName())) result.add(threads[i]);
    }
    return result;
  }

  private static void detach(Thread thread) {
    if (thread.getContextClassLoader() != NativeLibrary.class.getClassLoader()) {
      thread.setContextClassLoader(NativeLibrary.class.getClassLoader());
    }
    try {
      Field field = Thread.class.getDeclaredField("inheritedAccessControlContext");
      field.setAccessible(true);
      // null is what Thread.exit() leaves behind and what AccessControlContext.optimize() treats as "nothing
      // inherited"; setting it avoids referencing the deprecated-for-removal AccessControlContext class at all.
      field.set(thread, null);
    }
    catch (NoSuchFieldException e) {
      // Java 24+: nothing is inherited
    }
    catch (ReflectiveOperationException | RuntimeException e) {
      // no --add-opens java.base/java.lang: the thread keeps the plugin's domain until it ends
    }
  }
}
