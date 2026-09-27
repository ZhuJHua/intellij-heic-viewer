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
 * Opens native libraries through the IDE's bundled JNA without pinning the plugin class loader, so the plugin can be
 * installed, updated and removed without a restart.
 * <p>
 * <b>Rules for every backend</b> (checked by {@code PluginClassLoaderLeakTest}):
 * <ul>
 *   <li>Only JNA's untyped layer: {@link NativeLibrary} from {@link #open}, {@code NativeLibrary.getFunction(name)},
 *   {@code Function.invokeLong/invokeInt/invokeDouble/invokeVoid/invokePointer(Object[])} with arguments of JDK types
 *   ({@code Long}, {@code Integer}, {@code Double}, {@code byte[]}, {@code char[]}, {@code int[]}, {@code long[]}) and
 *   {@link com.sun.jna.Pointer}, plus {@link Native#malloc}/{@link Native#free}. Strings are passed as NUL-terminated
 *   arrays ({@link #utf8z}, {@link #utf16z}), because JNA copies {@code String} arguments into a {@code Memory}.</li>
 *   <li>No {@code Library} interface ({@code Native.load}), no {@code Structure}, {@code Union}, {@code ByReference},
 *   {@code Callback} or {@code NativeMapped} subclass and no {@code Memory}: JNA's static caches keyed by those classes
 *   never clear. Structures are read and written as bytes at known offsets; by-value structs are passed as their scalar
 *   members where the ABI allows it (see {@code cn.yooss.heic.mac.jna.JnaMacApi.RectPassing}).</li>
 *   <li>JNA's own classes and the IDE's {@code com.sun.jna.platform} classes may be used, but none of the plugin's
 *   classes may be handed to them.</li>
 *   <li>Libraries are opened with {@link #open}, never with {@code NativeLibrary.getInstance(name, options)} options
 *   that contain a class loader.</li>
 * </ul>
 * {@link #open} runs with JNA's class loader as the context class loader and clears the inherited access control
 * context of a "JNA Cleaner" thread started during the call: JNA may start that thread from the calling thread, which
 * would make it keep the plugin class loader.
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
      // null means "nothing inherited", as after Thread.exit()
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
