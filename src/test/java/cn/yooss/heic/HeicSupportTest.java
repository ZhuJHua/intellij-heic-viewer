package cn.yooss.heic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registration in the process-wide IIORegistry, as done by the application/dynamic-plugin listeners. Runs on every OS:
 * the reader is registered everywhere, and finding it for a HEIC file only sniffs bytes (no decoding).
 */
class HeicSupportTest {
  private final List<ImageReaderSpi> extra = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    HeicSupport.unregister();
    for (ImageReaderSpi spi : extra) IIORegistry.getDefaultInstance().deregisterServiceProvider(spi, ImageReaderSpi.class);
    extra.clear();
    assertEquals(0, countProviders(HeicImageReaderSpi.class.getName()), "nothing may be left in the registry");
  }

  @Test
  void registerAndUnregisterAreIdempotent() throws IOException {
    assertFalse(HeicSupport.isRegistered());
    assertTrue(HeicSupport.register());
    assertTrue(HeicSupport.register());
    assertTrue(HeicSupport.isRegistered());
    assertEquals(1, countProviders(HeicImageReaderSpi.class.getName()));
    assertInstanceOf(HeicImageReader.class, firstReader("rgb_sips.heic"));
    assertTrue(HeicSupport.isVisibleToImageIO());

    HeicSupport.unregister();
    HeicSupport.unregister();
    assertFalse(HeicSupport.isRegistered());
    assertEquals(0, countProviders(HeicImageReaderSpi.class.getName()));
    assertEquals(null, firstReader("rgb_sips.heic"));
  }

  @Test
  void settingsFallBackToDefaultsOutsideTheIde() {
    assertEquals("", HeicSettings.libheifPath());
  }

  @Test
  void ourReaderWinsOverAnotherHeifReader() throws IOException {
    OtherHeifReaderSpi other = new OtherHeifReaderSpi();
    IIORegistry.getDefaultInstance().registerServiceProvider(other, ImageReaderSpi.class);
    extra.add(other);

    HeicSupport.register();
    for (int i = 0; i < 5; i++) {
      assertInstanceOf(HeicImageReader.class, firstReader("rgb_sips.heic"));
    }
  }

  /** The ImageIO visibility check asks ImageIO for all "heic" readers; one that cannot be created must not break it. */
  @Test
  void visibilityCheckSurvivesABrokenHeicReaderOfAnotherPlugin() throws IOException {
    UncreatableHeicReaderSpi broken = new UncreatableHeicReaderSpi();
    IIORegistry.getDefaultInstance().registerServiceProvider(broken, ImageReaderSpi.class);
    extra.add(broken);

    assertTrue(HeicSupport.register());
    assertTrue(HeicSupport.isVisibleToImageIO());
    assertEquals(1, countProviders(HeicImageReaderSpi.class.getName()), "no second copy in the normal case");
    assertInstanceOf(HeicImageReader.class, firstReader("rgb_sips.heic"));
  }

  /**
   * After beforePluginUnload nothing may put the reader back (e.g. the diff tool hook while the platform flushes the
   * event queue before removing the extensions): it would pin the class loader being unloaded. Runs in a separate
   * "plugin class loader" because the flag is permanent for a class loader.
   */
  @Test
  void noRegistrationAfterShutDown() throws Exception {
    try (URLClassLoader pluginLoader = new PluginClassLoader(mainClassesRoot())) {
      Class<?> support = pluginLoader.loadClass(HeicSupport.class.getName());
      assertNotSame(HeicSupport.class, support);
      try {
        assertEquals(true, support.getMethod("register").invoke(null));
        assertEquals(1, countProviders(HeicImageReaderSpi.class.getName()));
        support.getMethod("shutDown").invoke(null);
        assertEquals(0, countProviders(HeicImageReaderSpi.class.getName()));
        assertEquals(false, support.getMethod("register").invoke(null));
        assertEquals(false, support.getMethod("isRegistered").invoke(null));
        assertEquals(0, countProviders(HeicImageReaderSpi.class.getName()));
      }
      finally {
        support.getMethod("shutDown").invoke(null);
      }
    }
    assertTrue(HeicSupport.register(), "a reloaded plugin (new class loader) registers again");
  }

  @Test
  void staleCopyFromAnOldPluginClassLoaderIsRemoved() throws Exception {
    try (URLClassLoader oldPluginLoader = new URLClassLoader(new URL[]{mainClassesRoot()}, ClassLoader.getPlatformClassLoader())) {
      Class<?> oldClass = oldPluginLoader.loadClass(HeicImageReaderSpi.class.getName());
      assertNotSame(HeicImageReaderSpi.class, oldClass);
      ImageReaderSpi stale = (ImageReaderSpi) oldClass.getConstructor().newInstance();
      IIORegistry.getDefaultInstance().registerServiceProvider(stale, ImageReaderSpi.class);
      extra.add(stale);
      assertEquals(1, countProviders(HeicImageReaderSpi.class.getName()), "only the stale copy");

      HeicSupport.register();
      assertEquals(1, countProviders(HeicImageReaderSpi.class.getName()), "the stale copy was replaced");
      Iterator<ImageReaderSpi> it = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, false);
      while (it.hasNext()) assertNotSame(stale, it.next());
      assertInstanceOf(HeicImageReader.class, firstReader("rgb_sips.heic"));
    }
  }

  /** Root of the directory/jar our classes come from (the test class loader does not always expose a CodeSource). */
  private static URL mainClassesRoot() throws IOException {
    String resource = HeicImageReaderSpi.class.getName().replace('.', '/') + ".class";
    String classFile = HeicImageReaderSpi.class.getClassLoader().getResource(resource).toString();
    return URI.create(classFile.substring(0, classFile.length() - resource.length())).toURL();
  }

  /** Loads the plugin's own classes anew (child first), everything else (platform, JDK) from the test class path. */
  private static final class PluginClassLoader extends URLClassLoader {
    PluginClassLoader(URL classes) {
      super(new URL[]{classes}, HeicSupportTest.class.getClassLoader());
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

  private static ImageReader firstReader(String fixture) throws IOException {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(fixture)))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      return readers.hasNext() ? readers.next() : null;
    }
  }

  private static int countProviders(String className) {
    int count = 0;
    Iterator<ImageReaderSpi> it = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, false);
    while (it.hasNext()) {
      if (it.next().getClass().getName().equals(className)) count++;
    }
    return count;
  }

  /** Another plugin's HEIC reader whose reader instances cannot be created. */
  private static final class UncreatableHeicReaderSpi extends ImageReaderSpi {
    UncreatableHeicReaderSpi() {
      super("broken", "1", new String[]{"heic"}, new String[]{"heic"}, null, "broken.Reader",
            new Class<?>[]{ImageInputStream.class}, null, false, null, null, null, null, false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) {
      return false;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
      throw new IllegalStateException("cannot create");
    }

    @Override
    public String getDescription(Locale locale) {
      return "broken HEIC reader";
    }
  }

  /** Stand-in for a HEIF reader from another plugin (e.g. a TwelveMonkeys/NightMonkeys HEIF reader). */
  private static final class OtherHeifReaderSpi extends ImageReaderSpi {
    OtherHeifReaderSpi() {
      super("other", "1", new String[]{"HEIF", "heif"}, new String[]{"heic"}, new String[]{"image/heif"},
            "other.Reader", new Class<?>[]{ImageInputStream.class}, null, false, null, null, null, null, false, null, null,
            null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) {
      return source instanceof ImageInputStream; // claims everything
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getDescription(Locale locale) {
      return "other HEIF reader";
    }
  }
}
