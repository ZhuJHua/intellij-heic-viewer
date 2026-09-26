package cn.yooss.heic;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.reflect.Field;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.DomainCombiner;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Releases the plugin class loader from threads that merely inherited it, right before the plugin is unloaded
 * ({@link HeicDynamicPluginListener#beforePluginUnload}).
 * <p>
 * <b>Why.</b> On Java 17-23 (IntelliJ 2024.1 on JBR 17, 2024.2-2025.3 on JBR 21) every new thread stores the
 * {@code AccessControlContext} of the code that created it: the {@code ProtectionDomain} of every class on the creating
 * stack, and a plugin class's domain references the plugin class loader. The thread keeps that context, and so the
 * class loader, for as long as it lives. Threads are created in plugin code far more often than it seems: submitting
 * work to the IDE's application pool starts a pool thread when none is idle (for example the decoder check that
 * {@code appFrameCreated} submits early during startup, or a thumbnail decode), and so do coroutine dispatches, timers
 * and JNA. {@code Executors.privilegedThreadFactory()}, which the application pool uses, does not help: it runs the tasks
 * with its own context, but the thread still stores its creator's. Such a pool thread lives until it has been idle for a
 * minute, and an unload in that time failed with "class loader cannot be unloaded" (reproduced on IntelliJ IDEA 2024.1.7:
 * the IDE's memory snapshot shows {@code Thread.inheritedAccessControlContext} -> {@code ProtectionDomain} ->
 * {@code PluginClassLoader}).
 * <p>
 * <b>What.</b> {@link #release()} goes through all live threads and replaces an inherited context that contains a
 * domain of this plugin's class loader with the same context without those domains (a domain combiner, e.g. that of a
 * JAAS {@code Subject}, is kept). Without a security manager, which IntelliJ-based IDEs never install, nothing checks
 * these contexts; a thread of the application pool even runs every task with its factory's context. The contexts are
 * read through public API only (a {@link DomainCombiner} is handed the domains of a context); writing the field needs
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED}, which every IDE launcher passes (without it nothing is changed).
 * Java 24 and newer no longer have inherited access control contexts: nothing to do.
 */
@SuppressWarnings("removal") // AccessController & co.: deprecated for removal since Java 17, gone in effect from Java 24
public final class InheritedContexts {
  private static final Logger LOG = Logger.getInstance(InheritedContexts.class);
  private static final String FIELD = "inheritedAccessControlContext";

  private InheritedContexts() {
  }

  /**
   * Before this plugin is unloaded: releases its class loader from the inherited access control contexts of all live
   * threads (see the class comment). Never throws.
   *
   * @return the names of the threads that were released
   */
  public static @NotNull List<String> release() {
    return release(InheritedContexts.class.getClassLoader());
  }

  @VisibleForTesting
  static @NotNull List<String> release(@NotNull ClassLoader loader) {
    List<String> released = new ArrayList<>();
    try {
      Field field = inheritedContextField();
      if (field == null) return released;
      for (Thread thread : liveThreads()) {
        if (!(field.get(thread) instanceof AccessControlContext context)) continue;
        ProtectionDomain[] domains = domainsOf(context);
        if (domains == null || !references(domains, loader)) continue;
        field.set(thread, without(context, domains, loader));
        released.add(thread.getName());
      }
    }
    catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      LOG.info("Cannot release the plugin class loader from inherited access control contexts", e);
    }
    return released;
  }

  /** {@code Thread.inheritedAccessControlContext}, accessible; {@code null} on Java 24+ or without the java.lang opening. */
  private static @Nullable Field inheritedContextField() {
    try {
      Field field = Thread.class.getDeclaredField(FIELD);
      field.setAccessible(true);
      return field;
    }
    catch (NoSuchFieldException e) {
      return null; // Java 24+: threads inherit no access control context
    }
    catch (RuntimeException e) {
      LOG.info("Cannot access Thread." + FIELD + " (no --add-opens java.base/java.lang=ALL-UNNAMED)");
      return null;
    }
  }

  /** All live platform threads. */
  private static List<Thread> liveThreads() {
    ThreadGroup root = Thread.currentThread().getThreadGroup();
    while (root.getParent() != null) root = root.getParent();
    Thread[] threads;
    int count;
    do {
      threads = new Thread[root.activeCount() + 32];
      count = root.enumerate(threads, true);
    }
    while (count == threads.length); // more threads than the estimate: enumerate again with a larger array
    return Arrays.asList(threads).subList(0, count);
  }

  /**
   * The protection domains of {@code context} ({@code null} if it has none, i.e. only system code). Public API only: while
   * {@code AccessController.getContext()} optimizes a context whose assigned context has a combiner, it hands the
   * combiner the assigned context's domains.
   */
  @VisibleForTesting
  static ProtectionDomain @Nullable [] domainsOf(@NotNull AccessControlContext context) {
    DomainSpy spy = new DomainSpy();
    AccessController.doPrivileged((PrivilegedAction<AccessControlContext>) AccessController::getContext,
                                  new AccessControlContext(context, spy));
    return spy.assigned;
  }

  private static boolean references(ProtectionDomain @NotNull [] domains, @NotNull ClassLoader loader) {
    for (ProtectionDomain domain : domains) {
      if (domain != null && domain.getClassLoader() == loader) return true;
    }
    return false;
  }

  /** {@code context} (with the given {@code domains}) without the domains of {@code loader}; {@code null} if nothing is left. */
  private static @Nullable AccessControlContext without(@NotNull AccessControlContext context, ProtectionDomain @NotNull [] domains,
                                                        @NotNull ClassLoader loader) {
    List<ProtectionDomain> kept = new ArrayList<>(domains.length);
    for (ProtectionDomain domain : domains) {
      if (domain != null && domain.getClassLoader() != loader) kept.add(domain);
    }
    DomainCombiner combiner = context.getDomainCombiner();
    if (kept.isEmpty() && combiner == null) return null; // what Thread.exit() leaves behind: nothing inherited
    AccessControlContext rest = new AccessControlContext(kept.toArray(new ProtectionDomain[0]));
    return combiner == null ? rest : new AccessControlContext(rest, combiner);
  }

  /** Records the assigned domains it is asked to combine, and changes nothing. */
  private static final class DomainSpy implements DomainCombiner {
    ProtectionDomain[] assigned;

    @Override
    public ProtectionDomain[] combine(ProtectionDomain[] currentDomains, ProtectionDomain[] assignedDomains) {
      assigned = assignedDomains;
      return currentDomains;
    }
  }
}
