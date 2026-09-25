package cn.yooss.heic;

import com.intellij.openapi.diagnostic.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registers the single {@link HeicImageReaderSpi} instance in the process-wide {@link IIORegistry} and removes it
 * again before the plugin is unloaded (a provider left in the registry would pin the plugin class loader).
 * <p>
 * Called from {@link HeicAppLifecycleListener} (normal start), {@link HeicReaderRegistrar} (the command-line diff and
 * merge starters, which never create an IDE frame) and {@link HeicDynamicPluginListener} (install, enable, update and
 * uninstall without restart). Never touches native code: the decoder is loaded lazily on the first HEIC image.
 * <p>
 * <b>Split registry.</b> {@code ImageIO} (and therefore the IDE's {@code IfsUtil}) only searches the registry it
 * captured in its static initializer. {@link IIORegistry#getDefaultInstance()} is not thread-safe: when two threads
 * call it for the first time at once, each creates a registry, and ImageIO can end up with one that
 * {@code getDefaultInstance()} no longer returns (reproduced on Android Studio 2026.2 / JBR 25, where the splash screen
 * initializes ImageIO while the platform's {@code ImageReaderWriterSpiRegistrar} registers its readers). A reader
 * registered through {@code getDefaultInstance()} is then invisible to ImageIO for the whole session. Registration
 * therefore checks what ImageIO sees and, in that case, also registers a second instance into ImageIO's registry.
 */
public final class HeicSupport {
  public static final String PLUGIN_ID = "cn.yooss.heic-viewer";
  private static final Logger LOG = Logger.getInstance(HeicSupport.class);

  /** Registered in {@link #registeredIn}, the registry {@code IIORegistry.getDefaultInstance()} returned. */
  private static HeicImageReaderSpi registered;
  private static ServiceRegistry registeredIn;
  /** Only when ImageIO uses another registry: the instance ImageIO created in it, and that registry. */
  private static HeicImageReaderSpi imageIoCopy;
  private static ServiceRegistry imageIoCopyIn;
  /** Set by {@link #shutDown()}; a reloaded plugin has a new class loader and therefore a fresh flag. */
  private static boolean shutDown;

  private HeicSupport() {
  }

  /**
   * Registers the reader if it is not registered yet, on every OS: whether the system decoder can be used is decided
   * by the backend when an image is read ({@link cn.yooss.heic.backend.HeifBackend#status()}). Returns {@code true} if
   * the reader is registered afterwards ({@code false} only after {@link #shutDown()}).
   */
  public static synchronized boolean register() {
    if (registered != null) return true;
    if (shutDown) return false; // this class loader is being unloaded: a new registration would pin it
    register(new HeicImageReaderSpi(HeicSettings::decodeLimits));
    return true;
  }

  static synchronized void register(HeicImageReaderSpi spi) {
    if (registered != null) return;
    IIORegistry registry = IIORegistry.getDefaultInstance();
    removeStaleCopies(registry);
    registry.registerServiceProvider(spi, ImageReaderSpi.class);
    registered = spi;
    registeredIn = registry;
    List<String> others = preferOverOtherHeifReaders(registry, spi);
    LOG.info("HEIC ImageReaderSpi registered (decoder: " + decoderName(spi) + ", " + System.getProperty("os.name") + " "
             + System.getProperty("os.arch") + ", Java " + System.getProperty("java.version") + ")"
             + (others.isEmpty() ? "" : "; preferred over " + others));
    makeVisibleToImageIO(registry);
  }

  private static String decoderName(HeicImageReaderSpi spi) {
    try {
      return spi.backend().displayName();
    }
    catch (RuntimeException | LinkageError e) {
      return "unknown (" + e + ")";
    }
  }

  /** Deregisters the reader (idempotent), from ImageIO's own registry as well if it had to be added there. */
  public static synchronized void unregister() {
    HeicImageReaderSpi spi = registered;
    if (spi == null) return;
    boolean removed = registeredIn.deregisterServiceProvider(spi, ImageReaderSpi.class);
    registered = null;
    registeredIn = null;
    String copy = "";
    if (imageIoCopy != null) {
      boolean removedCopy = imageIoCopyIn.deregisterServiceProvider(imageIoCopy, ImageReaderSpi.class);
      imageIoCopy = null;
      imageIoCopyIn = null;
      copy = removedCopy ? ", also from ImageIO's registry" : ", ImageIO's copy was no longer registered";
    }
    LOG.info("HEIC ImageReaderSpi unregistered" + (removed ? "" : " (it was no longer registered)") + copy);
  }

  /**
   * Before this plugin is unloaded: deregisters the reader for good. {@link #register()} is a no-op afterwards, so a
   * late call (for example from {@link HeicReaderRegistrar} while the platform flushes the event queue between
   * {@code beforePluginUnload} and the removal of the extensions) cannot put the reader back into the registry.
   */
  public static synchronized void shutDown() {
    shutDown = true;
    unregister();
  }

  public static synchronized boolean isRegistered() {
    return registered != null;
  }

  /** Whether {@code ImageIO.getImageReaders} (what the IDE's image viewer uses) finds our reader. */
  static synchronized boolean isVisibleToImageIO() {
    return registered != null && providerSeenByImageIO() != null;
  }

  /**
   * Makes sure ImageIO sees the reader (see the class comment). In the normal case ImageIO's registry is
   * {@code defaultRegistry} and this only looks. Never throws: at worst HEIC images do not load, as before.
   */
  private static void makeVisibleToImageIO(IIORegistry defaultRegistry) {
    try {
      if (providerSeenByImageIO() != null) return;

      HeicImageReaderSpi copy = registerThroughImageIO();
      ServiceRegistry imageIoRegistry = copy == null ? null : copy.registry();
      if (imageIoRegistry == null) {
        LOG.warn("The HEIC reader is registered in IIORegistry.getDefaultInstance() (" + id(defaultRegistry)
                 + "), but ImageIO does not use that registry and it could not be added to ImageIO's; HEIC images "
                 + "will not load in this session. Restarting the IDE usually helps.");
        return;
      }
      imageIoCopy = copy;
      imageIoCopyIn = imageIoRegistry;
      removeStaleCopies(imageIoRegistry);
      preferOverOtherHeifReaders(imageIoRegistry, copy);
      LOG.warn("ImageIO does not use IIORegistry.getDefaultInstance() (" + id(defaultRegistry) + " vs ImageIO's "
               + id(imageIoRegistry) + "): two registries were created while the IDE started, because "
               + "IIORegistry.getDefaultInstance() is not thread-safe. The HEIC reader was added to ImageIO's registry "
               + "as well. Other readers ImageIO cannot see in this session: "
               + missingReaders(defaultRegistry, imageIoRegistry));
    }
    catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
      LOG.warn("Cannot check whether ImageIO sees the HEIC reader", e);
    }
  }

  /**
   * Our provider as ImageIO sees it, or {@code null}. Asks ImageIO for its HEIC readers, which creates an instance of
   * each (cheap: nothing is read or decoded).
   */
  private static HeicImageReaderSpi providerSeenByImageIO() {
    HeicImageReaderSpi found = null;
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(HeicImageReaderSpi.FORMAT_NAMES[0]);
    while (readers.hasNext()) {
      ImageReader reader;
      try {
        reader = readers.next();
      }
      catch (RuntimeException e) {
        continue; // another plugin's HEIC reader that cannot be created
      }
      if (reader == null) continue; // a provider whose createReaderInstance threw IOException
      if (found == null && reader.getOriginatingProvider() instanceof HeicImageReaderSpi ours) found = ours;
      reader.dispose();
    }
    return found;
  }

  /**
   * Registers a new instance of our provider in the registry ImageIO uses and returns it ({@code null} if that did not
   * work). That registry is private to ImageIO; the only public way into it is {@link ImageIO#scanForPlugins()}, which
   * registers the providers named in the {@code META-INF/services} files of the context class loader. The class
   * loader used here offers exactly one such file, naming our provider, so nothing else is loaded or re-registered.
   * The instance is created with the public no-argument constructor; it records its registry when registered.
   */
  private static HeicImageReaderSpi registerThroughImageIO() {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(new OnlyOurProvider(HeicImageReaderSpi.class.getClassLoader()));
    try {
      ImageIO.scanForPlugins();
    }
    finally {
      thread.setContextClassLoader(previous);
    }
    HeicImageReaderSpi copy = providerSeenByImageIO();
    return copy == registered ? null : copy;
  }

  /** Loads classes from the plugin; its only resource is a service file naming {@link HeicImageReaderSpi}. */
  private static final class OnlyOurProvider extends ClassLoader {
    private static final String SERVICE_FILE = "META-INF/services/" + ImageReaderSpi.class.getName();
    private final URL serviceFile;

    OnlyOurProvider(ClassLoader parent) {
      super(parent);
      byte[] content = (HeicImageReaderSpi.class.getName() + "\n").getBytes(StandardCharsets.UTF_8);
      URLStreamHandler inMemory = new URLStreamHandler() {
        @Override
        protected URLConnection openConnection(URL url) {
          return new URLConnection(url) {
            @Override
            public void connect() {
              connected = true;
            }

            @Override
            public InputStream getInputStream() {
              return new ByteArrayInputStream(content);
            }
          };
        }
      };
      try {
        // new URL(context, spec, handler): URL.of(URI, handler) needs Java 20.
        serviceFile = new URL(null, "heic-viewer:/" + SERVICE_FILE, inMemory);
      }
      catch (MalformedURLException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public Enumeration<URL> getResources(String name) {
      return SERVICE_FILE.equals(name) ? Collections.enumeration(List.of(serviceFile)) : Collections.emptyEnumeration();
    }

    @Override
    public URL getResource(String name) {
      return SERVICE_FILE.equals(name) ? serviceFile : null;
    }
  }

  /** Reader providers (class names) in {@code all} that {@code subset} has no provider of the same class for. */
  private static Set<String> missingReaders(ServiceRegistry all, ServiceRegistry subset) {
    Set<String> missing = readerClassNames(all);
    missing.removeAll(readerClassNames(subset));
    missing.remove(HeicImageReaderSpi.class.getName());
    return missing;
  }

  private static Set<String> readerClassNames(ServiceRegistry registry) {
    Set<String> names = new TreeSet<>();
    Iterator<ImageReaderSpi> it = registry.getServiceProviders(ImageReaderSpi.class, false);
    while (it.hasNext()) names.add(it.next().getClass().getName());
    return names;
  }

  private static String id(Object o) {
    return o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o));
  }

  /**
   * The registry keys providers by class, so a provider left behind by an older class loader of this plugin (for
   * example after an unload that did not complete) would stay registered next to ours. Remove such copies.
   */
  private static void removeStaleCopies(ServiceRegistry registry) {
    List<ImageReaderSpi> stale = new ArrayList<>();
    Iterator<ImageReaderSpi> it = registry.getServiceProviders(ImageReaderSpi.class, false);
    while (it.hasNext()) {
      ImageReaderSpi provider = it.next();
      if (provider.getClass() != HeicImageReaderSpi.class && provider.getClass().getName().equals(HeicImageReaderSpi.class.getName())) {
        stale.add(provider);
      }
    }
    for (ImageReaderSpi provider : stale) {
      registry.deregisterServiceProvider(provider, ImageReaderSpi.class);
      LOG.warn("Removed a stale HEIC ImageReaderSpi left by a previous plugin class loader: " + provider.getClass().getClassLoader());
    }
  }

  /**
   * ImageIO returns readers in no stable order unless an ordering is set. If another plugin registered a HEIC/HEIF
   * reader, make ours win so that the built-in viewer always gets the same (orientation-aware) result.
   */
  private static List<String> preferOverOtherHeifReaders(ServiceRegistry registry, HeicImageReaderSpi ours) {
    List<String> others = new ArrayList<>();
    Iterator<ImageReaderSpi> it = registry.getServiceProviders(ImageReaderSpi.class, false);
    while (it.hasNext()) {
      ImageReaderSpi provider = it.next();
      if (provider != ours && readsHeif(provider)) {
        registry.setOrdering(ImageReaderSpi.class, ours, provider);
        others.add(provider.getClass().getName());
      }
    }
    return others;
  }

  private static boolean readsHeif(ImageReaderSpi provider) {
    String[] names = provider.getFormatNames();
    if (names == null) return false;
    for (String name : names) {
      String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
      if (lower.contains("heic") || lower.contains("heif")) return true;
    }
    return false;
  }
}
