package cn.yooss.heic;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.URI;
import java.security.AccessControlContext;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link InheritedContexts}: a thread started while code of a class loader was on the stack keeps that loader alive
 * through its inherited access control context (JDK 17-23), until it is released. The unload failure it fixes was
 * reproduced on IntelliJ IDEA 2024.1.7: an application pool thread started by the decoder check at startup. Here a
 * {@link Timer} thread stands in for it (its thread references only JDK objects, like an idle pool thread).
 */
@SuppressWarnings("removal")
class InheritedContextsTest {
  @Test
  void releasesTheLoaderFromAThreadItStarted() throws Exception {
    assumeTrue(threadsInheritContexts(), "Java 24+: threads inherit no access control context");
    Timer timer = null;
    try {
      StarterLoader loader = new StarterLoader();
      timer = (Timer) loader.start();
      String name = timerThreadName(loader);
      WeakReference<ClassLoader> ref = new WeakReference<>(loader);
      loader = null;
      assertFalse(collect(ref), "control: the timer thread must keep the loader alive");

      assertEquals(List.of(name), InheritedContexts.release(ref.get()));
      assertTrue(collect(ref), "released, the loader must be collectable although the thread still runs");
    }
    finally {
      if (timer != null) timer.cancel();
    }
  }

  @Test
  void keepsTheDomainsOfOtherLoaders() throws Exception {
    assumeTrue(threadsInheritContexts(), "Java 24+: threads inherit no access control context");
    StarterLoader loader = new StarterLoader();
    Timer timer = (Timer) loader.start();
    try {
      Thread thread = thread(timerThreadName(loader));
      ProtectionDomain ours = InheritedContextsTest.class.getProtectionDomain(); // this class started it, via the loader's
      List<ProtectionDomain> before = Arrays.asList(domains(thread));
      assertTrue(before.contains(ours), before.toString());
      assertTrue(before.stream().anyMatch(d -> d != null && d.getClassLoader() == loader), before.toString());

      InheritedContexts.release(loader);
      List<ProtectionDomain> after = Arrays.asList(domains(thread));
      assertTrue(after.contains(ours), "domains of other class loaders stay: " + after);
      assertTrue(after.stream().noneMatch(d -> d != null && d.getClassLoader() == loader), after.toString());
      assertTrue(InheritedContexts.release(loader).isEmpty(), "nothing left to release");
    }
    finally {
      timer.cancel();
    }
  }

  @Test
  void releasesNothingWhenNoThreadReferencesTheLoader() {
    assertTrue(InheritedContexts.release(new StarterLoader()).isEmpty());
  }

  @Test
  void readsTheDomainsOfAContext() {
    assumeTrue(threadsInheritContexts(), "Java 24+: access control contexts carry no domains any more");
    ProtectionDomain domain = new ProtectionDomain(new CodeSource(null, (Certificate[]) null), null);
    ProtectionDomain[] domains = InheritedContexts.domainsOf(new AccessControlContext(new ProtectionDomain[]{domain}));
    assertNotNull(domains);
    assertEquals(List.of(domain), Arrays.asList(domains));
    assertNull(InheritedContexts.domainsOf(new AccessControlContext(new ProtectionDomain[0])));
  }

  /** Whether this JVM has {@code Thread.inheritedAccessControlContext} (Java 17-23). */
  static boolean threadsInheritContexts() {
    try {
      Thread.class.getDeclaredField("inheritedAccessControlContext");
      return true;
    }
    catch (NoSuchFieldException e) {
      return false;
    }
  }

  private static ProtectionDomain[] domains(Thread thread) throws ReflectiveOperationException {
    Field field = Thread.class.getDeclaredField("inheritedAccessControlContext");
    field.setAccessible(true);
    AccessControlContext context = (AccessControlContext) field.get(thread);
    if (context == null) return new ProtectionDomain[0];
    ProtectionDomain[] domains = InheritedContexts.domainsOf(context);
    return domains == null ? new ProtectionDomain[0] : domains;
  }

  private static String timerThreadName(ClassLoader loader) {
    return "inherited-contexts-test@" + Integer.toHexString(System.identityHashCode(loader));
  }

  private static Thread thread(String name) {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals(name)) return thread;
    }
    throw new AssertionError("no thread " + name);
  }

  private static boolean collect(WeakReference<?> ref) throws InterruptedException {
    for (int i = 0; i < 100 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(25);
    }
    return ref.get() == null;
  }

  /** Started by {@link StarterLoader} in its own class: a daemon {@link Timer}, whose thread is created right here. */
  public static final class Starter implements Callable<Object> {
    @Override
    public Object call() {
      ClassLoader loader = Starter.class.getClassLoader();
      return new Timer("inherited-contexts-test@" + Integer.toHexString(System.identityHashCode(loader)), true);
    }
  }

  /**
   * Defines {@link Starter} itself (everything else comes from the parent), with a protection domain that references
   * this loader, like a plugin class loader.
   */
  static final class StarterLoader extends SecureClassLoader {
    StarterLoader() {
      super(InheritedContextsTest.class.getClassLoader());
    }

    Object start() throws Exception {
      @SuppressWarnings("unchecked")
      Callable<Object> starter = (Callable<Object>) loadClass(Starter.class.getName()).getConstructor().newInstance();
      return starter.call();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(Starter.class.getName())) return super.loadClass(name, resolve);
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) return loaded;
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = getParent().getResourceAsStream(resource)) {
          byte[] bytes = in.readAllBytes();
          return defineClass(name, bytes, 0, bytes.length,
                             new CodeSource(URI.create("file:/inherited-contexts-test/").toURL(), (Certificate[]) null));
        }
        catch (Exception e) {
          throw new ClassNotFoundException(name, e);
        }
      }
    }
  }
}
