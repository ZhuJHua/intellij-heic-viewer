package cn.yooss.heic;

import com.intellij.openapi.diagnostic.DefaultLogger;
import com.intellij.openapi.diagnostic.Logger;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStreamImpl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic unload: after the plugin has decoded images, registered its reader and shut down again, nothing may keep
 * its class loader alive, or the IDE needs a restart to update or remove the plugin.
 * <p>
 * Runs in a fresh JVM ({@link Child}) so that JNA is "cold" (its native part not loaded, its Cleaner thread not
 * started yet), the worst case: the plugin's classes are loaded again by a child-first class loader, like the IDE's
 * plugin class loader, while JNA and the JDK come from the parent. The child exercises the backend of this OS, the reader
 * registration and ImageIO, on the main thread and on a pool thread created before the plugin, with the plugin loader
 * as context class loader, then drops every reference and checks that the loader is garbage collected. The same test
 * runs on JDK 17, 21 and 25 (the IDE runtimes), where different things can pin a class loader (JDK 17: record
 * bootstraps; JDK 17-23: access control contexts inherited by threads the plugin starts, such as JNA's Cleaner, or a pool
 * thread that plugin code happens to start by submitting a task, like the decoder check does in the IDE's application
 * pool at startup; {@link InheritedContexts} releases those before the plugin is unloaded).
 */
class PluginClassLoaderLeakTest {
  @Test
  void pluginClassLoaderIsCollectedAfterUse() throws Exception {
    Map<String, String> results = runChild();
    String output = results.get("output");
    assertEquals("true", results.get("exercised"), output);
    assertEquals("true", results.get("collected"), "the plugin class loader must be collectable:\n" + output);
  }

  /**
   * Control: a pool thread started while plugin code is on the stack (here from a logger that plugin code calls, like
   * the IDE's application pool thread that the decoder check starts) keeps the loader alive on JDK 17-23 if the inherited
   * access control contexts are not released.
   */
  @Test
  void threadStartedByPluginCodeKeepsTheLoaderUnlessReleased() throws Exception {
    Map<String, String> results = runChild("skip-release");
    assertEquals("true", results.get("exercised"), results.get("output"));
    assertTrue(Integer.parseInt(results.get("pluginStartedThreads")) >= 1, results.get("output"));
    boolean inherits = Boolean.parseBoolean(results.get("threadsInheritContexts"));
    assertEquals(String.valueOf(!inherits), results.get("collected"),
                 (inherits ? "JDK 17-23: the pool thread must pin the loader:\n" : "JDK 24+: nothing is inherited:\n") + results.get("output"));
  }

  /** Control: without the unload steps (the reader stays registered in ImageIO) the loader must stay reachable. */
  @Test
  void leakIsDetected() throws Exception {
    Map<String, String> results = runChild("skip-shutdown");
    assertEquals("true", results.get("exercised"), results.toString());
    assertEquals("false", results.get("collected"), "the check must notice a pinned class loader");
  }

  private static Map<String, String> runChild(String... options) throws Exception {
    List<String> arguments = new ArrayList<>();
    arguments.add(mainClassesRoot().toString());
    arguments.addAll(List.of(options));
    ChildJvm.Result result = ChildJvm.run(Child.class, 180, arguments.toArray(new String[0]));
    System.out.println(result.output);
    assertEquals(0, result.exitCode, result.output);
    Map<String, String> values = result.values();
    values.put("output", result.output);
    return values;
  }

  /** Root of the directory/jar our main classes come from. */
  static URL mainClassesRoot() throws IOException {
    String resource = HeicSupport.class.getName().replace('.', '/') + ".class";
    String classFile = HeicSupport.class.getClassLoader().getResource(resource).toString();
    return URI.create(classFile.substring(0, classFile.length() - resource.length())).toURL();
  }

  /** Loads the plugin's own classes anew (child first), everything else (JDK, JNA, tests) from the parent. */
  static final class PluginLoader extends URLClassLoader {
    PluginLoader(URL classes, ClassLoader parent) {
      super(new URL[]{classes}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith("cn.yooss.heic.")) return super.loadClass(name, resolve);
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          try {
            loaded = findClass(name);
          }
          catch (ClassNotFoundException e) {
            return super.loadClass(name, resolve); // test classes
          }
        }
        if (resolve) resolveClass(loaded);
        return loaded;
      }
    }
  }

  /** Runs in a fresh JVM: {@code args[0]} is the root of the plugin classes; {@code skip-shutdown} omits the unload steps. */
  @SuppressWarnings("removal") // Executors.privilegedThreadFactory, AccessControlContext (JDK 17-23 behavior under test)
  public static final class Child {
    public static void main(String[] args) {
      int exitCode = 1;
      try {
        run(args);
        exitCode = 0;
      }
      catch (Throwable t) {
        t.printStackTrace(System.out);
      }
      finally {
        System.exit(exitCode); // also when a non-daemon thread is left
      }
    }

    private static void run(String[] args) throws Exception {
      URL root = URI.create(args[0]).toURL();
      byte[] heic = Fixtures.bytes("alpha_sips.heic");
      // What the IDE has done long before a plugin loads: ImageIO's registry and Java2D are initialized (their global
      // state, e.g. the AppContext, captures the context class loader of the thread that initializes them). JNA stays
      // cold on purpose: the plugin's first native call initializes it.
      ImageIO.read(new ByteArrayInputStream(Fixtures.bytes("rgb.png"))).createGraphics().dispose();
      // A pool thread that exists before the plugin, like the IDE's application pool.
      ExecutorService pool = Executors.newSingleThreadExecutor();
      pool.submit(() -> { }).get();
      // The factory of the pools that plugin code may make start a thread, created before the plugin like the IDE's
      // application pool (whose threads run their tasks with the factory's access control context and class loader).
      poolThreads = Executors.privilegedThreadFactory();
      out("threadsInheritContexts", InheritedContextsTest.threadsInheritContexts());

      List<String> options = List.of(args);
      boolean skipShutdown = options.contains("skip-shutdown");
      boolean skipRelease = options.contains("skip-release");
      List<String> suspects = new ArrayList<>();
      WeakReference<ClassLoader> ref = runPlugin(root, heic, pool, suspects, skipShutdown, skipRelease);
      boolean collected = collect(ref);
      out("collected", collected);
      out("pluginStartedThreads", PLUGIN_STARTED_POOLS.size());
      if (!collected) for (String suspect : suspects) System.out.println("SUSPECT " + suspect);
      pool.shutdownNow();
      for (ExecutorService started : PLUGIN_STARTED_POOLS) started.shutdownNow();
    }

    /** Pools whose thread plugin code started (see {@link #startPoolThread}); shut down after the check. */
    private static final List<ExecutorService> PLUGIN_STARTED_POOLS = new ArrayList<>();
    private static ThreadFactory poolThreads;

    /**
     * Called by plugin code (a warning logged by {@code HeicImageReaderSpi.canDecodeInput}): submits a task to a new
     * pool, which starts its thread right here, with the plugin's classes on the stack.
     */
    private static void startPoolThread() {
      ExecutorService executor = Executors.newSingleThreadExecutor(poolThreads);
      try {
        executor.submit(() -> { }).get(60, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        throw new IllegalStateException(e);
      }
      PLUGIN_STARTED_POOLS.add(executor);
    }

    /** Everything that references the plugin loader stays inside this frame. */
    private static WeakReference<ClassLoader> runPlugin(URL root, byte[] heic, ExecutorService pool, List<String> suspects,
                                                        boolean skipShutdown, boolean skipRelease) throws Exception {
      PluginLoader loader = new PluginLoader(root, Child.class.getClassLoader());
      WeakReference<ClassLoader> ref = new WeakReference<>(loader);
      Runnable work = () -> {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader); // the worst case: plugin code running with its own context loader
        try {
          exercise(loader, heic);
        }
        catch (Exception e) {
          throw new IllegalStateException(e);
        }
        finally {
          thread.setContextClassLoader(previous);
        }
      };
      work.run();
      pool.submit(work).get(60, TimeUnit.SECONDS);
      if (!skipShutdown) {
        // Before the plugin is unloaded (HeicDynamicPluginListener.beforePluginUnload).
        loader.loadClass("cn.yooss.heic.HeicSupport").getMethod("shutDown").invoke(null);
        loader.loadClass("cn.yooss.heic.backend.HeifBackends").getMethod("shutDown").invoke(null);
        if (!skipRelease) out("released", loader.loadClass("cn.yooss.heic.InheritedContexts").getMethod("release").invoke(null));
      }
      out("exercised", true);
      suspects.addAll(threadSuspects(loader));
      loader.close();
      return ref;
    }

    private static void exercise(ClassLoader loader, byte[] heic) throws Exception {
      Class<?> backends = loader.loadClass("cn.yooss.heic.backend.HeifBackends");
      Object backend = backends.getMethod("current").invoke(null);
      Class<?> backendType = loader.loadClass("cn.yooss.heic.backend.HeifBackend");
      Object status = backendType.getMethod("status").invoke(backend);
      boolean available = (Boolean) status.getClass().getMethod("isAvailable").invoke(status);
      out("backend", backendType.getMethod("id").invoke(backend) + " " + status);
      if (available) {
        Object info = backendType.getMethod("readInfo", byte[].class).invoke(backend, heic);
        out("info", info.toString() + " hash " + info.hashCode() + " equals " + info.equals(info));
        backendType.getMethod("decode", byte[].class, int.class).invoke(backend, heic, 0);
        backendType.getMethod("decode", byte[].class, int.class).invoke(backend, heic, 64);
      }
      // A value class (hand-written equals/hashCode/toString, not a record).
      out("status", status.equals(status) + " " + status.hashCode());

      // Plugin code that calls out with its classes on the stack: canDecodeInput logs a failing stream, and the logger
      // starts a pool thread (see startPoolThread).
      Logger.Factory previous = Logger.getFactory();
      Logger.setFactory(category -> new DefaultLogger(category) {
        @Override
        public void warn(String message, Throwable t) {
          startPoolThread();
        }
      });
      try {
        Object spi = loader.loadClass("cn.yooss.heic.HeicImageReaderSpi").getConstructor().newInstance();
        spi.getClass().getMethod("canDecodeInput", Object.class).invoke(spi, new FailingStream());
      }
      finally {
        Logger.setFactory(previous);
      }

      // The reader, registered like the plugin does; ImageIO decodes through it.
      loader.loadClass("cn.yooss.heic.HeicSupport").getMethod("register").invoke(null);
      try {
        java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(heic));
        out("imageio", image == null ? "no reader" : image.getWidth() + "x" + image.getHeight());
      }
      catch (IOException e) {
        out("imageio", "IOException " + e.getMessage());
      }
    }

    /** Threads that keep the plugin loader alive: context class loader or (JDK 17-23) inherited access control context. */
    private static List<String> threadSuspects(ClassLoader loader) {
      List<String> suspects = new ArrayList<>();
      for (Thread thread : Thread.getAllStackTraces().keySet()) {
        if (thread.getContextClassLoader() == loader) suspects.add("thread '" + thread.getName() + "': context class loader");
        try {
          Field inherited = Thread.class.getDeclaredField("inheritedAccessControlContext");
          inherited.setAccessible(true);
          Object context = inherited.get(thread);
          if (context == null) continue;
          java.security.ProtectionDomain[] domains = InheritedContexts.domainsOf((java.security.AccessControlContext) context);
          if (domains == null) continue;
          for (java.security.ProtectionDomain domain : domains) {
            if (domain.getClassLoader() == loader) suspects.add("thread '" + thread.getName() + "': inherited access control context");
          }
        }
        catch (NoSuchFieldException e) {
          break; // Java 24+: no inherited access control contexts
        }
        catch (ReflectiveOperationException | RuntimeException e) {
          suspects.add("cannot inspect thread '" + thread.getName() + "': " + e);
        }
      }
      return suspects;
    }

    /** A stream whose reads fail. */
    private static final class FailingStream extends ImageInputStreamImpl {
      @Override
      public int read() {
        throw new IllegalStateException("read failed");
      }

      @Override
      public int read(byte[] b, int off, int len) {
        throw new IllegalStateException("read failed");
      }
    }

    private static boolean collect(WeakReference<ClassLoader> ref) throws InterruptedException {
      for (int i = 0; i < 100 && ref.get() != null; i++) {
        System.gc();
        byte[][] junk = new byte[16][];
        for (int k = 0; k < junk.length; k++) junk[k] = new byte[1 << 20];
        Thread.sleep(50);
      }
      return ref.get() == null;
    }

    private static void out(String key, Object value) {
      System.out.println("RESULT " + key + "=" + value);
    }
  }
}
