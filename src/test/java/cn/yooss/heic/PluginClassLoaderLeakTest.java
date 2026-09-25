package cn.yooss.heic;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * bootstraps; JDK 17-23: access control contexts inherited by threads the plugin starts, such as JNA's Cleaner).
 */
class PluginClassLoaderLeakTest {
  @Test
  void pluginClassLoaderIsCollectedAfterUse() throws Exception {
    Map<String, String> results = runChild();
    String output = results.get("output");
    assertEquals("true", results.get("exercised"), output);
    assertEquals("true", results.get("collected"), "the plugin class loader must be collectable:\n" + output);
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
    ProcessBuilder builder = new ProcessBuilder(ChildJvm.command(Child.class, arguments.toArray(new String[0])));
    builder.redirectErrorStream(true);
    Process process = builder.start();
    process.getOutputStream().close();
    byte[] out = process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child JVM timed out");
    String output = new String(out, StandardCharsets.UTF_8);
    System.out.println(output);
    assertEquals(0, process.exitValue(), output);
    Map<String, String> results = new HashMap<>();
    for (String line : output.split("\n")) {
      if (!line.startsWith("RESULT ")) continue;
      int eq = line.indexOf('=');
      results.put(line.substring("RESULT ".length(), eq), line.substring(eq + 1).trim());
    }
    results.put("output", output);
    return results;
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
  public static final class Child {
    public static void main(String[] args) throws Exception {
      URL root = URI.create(args[0]).toURL();
      byte[] heic = Fixtures.bytes("alpha_sips.heic");
      // What the IDE has done long before a plugin loads: ImageIO's registry and Java2D are initialized (their global
      // state, e.g. the AppContext, captures the context class loader of the thread that initializes them). JNA stays
      // cold on purpose: the plugin's first native call initializes it.
      ImageIO.read(new ByteArrayInputStream(Fixtures.bytes("rgb.png"))).createGraphics().dispose();
      // A pool thread that exists before the plugin, like the IDE's application pool.
      ExecutorService pool = Executors.newSingleThreadExecutor();
      pool.submit(() -> { }).get();

      boolean skipShutdown = List.of(args).contains("skip-shutdown");
      List<String> suspects = new ArrayList<>();
      WeakReference<ClassLoader> ref = runPlugin(root, heic, pool, suspects, skipShutdown);
      boolean collected = collect(ref);
      out("collected", collected);
      if (!collected) for (String suspect : suspects) System.out.println("SUSPECT " + suspect);
      pool.shutdownNow();
      System.exit(0);
    }

    /** Everything that references the plugin loader stays inside this frame. */
    private static WeakReference<ClassLoader> runPlugin(URL root, byte[] heic, ExecutorService pool, List<String> suspects,
                                                        boolean skipShutdown) throws Exception {
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
        backendType.getMethod("decodeThumbnail", byte[].class, int.class).invoke(backend, heic, 64);
      }
      // Value classes that used to be records (hand-written equals/hashCode/toString now).
      Class<?> limits = loader.loadClass("cn.yooss.heic.DecodeLimits");
      Object defaults = limits.getField("DEFAULT").get(null);
      Object other = limits.getConstructor(long.class, int.class).newInstance(10_000L, 300);
      out("limits", defaults.equals(other) + " " + defaults.hashCode() + " " + other);

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
          Field domains = context.getClass().getDeclaredField("context");
          domains.setAccessible(true);
          Object[] array = (Object[]) domains.get(context);
          if (array == null) continue;
          for (Object domain : array) {
            Method classLoader = domain.getClass().getMethod("getClassLoader");
            if (classLoader.invoke(domain) == loader) suspects.add("thread '" + thread.getName() + "': inherited access control context");
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
