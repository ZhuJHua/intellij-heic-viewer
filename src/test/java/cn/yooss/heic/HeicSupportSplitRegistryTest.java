package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackends;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the "IfsUtil returns null for every HEIC file although the reader is registered" incident
 * (Android Studio 2026.2 canary, one cold start): {@code IIORegistry.getDefaultInstance()} is not thread-safe, and when
 * two threads call it for the first time at once, ImageIO can keep a registry that {@code getDefaultInstance()} no
 * longer returns. A reader registered through {@code getDefaultInstance()} is then invisible to ImageIO.
 * <p>
 * The race needs a JVM in which ImageIO has not been initialized yet, so it runs in a child JVM ({@link Child}),
 * which forces the interleaving deterministically. Runs on every OS; the image is only decoded where the system decoder is
 * available.
 */
class HeicSupportSplitRegistryTest {
  @Test
  void readerReachesImageIOEvenIfImageIOUsesAnotherRegistry() throws Exception {
    Result child = runChild();
    Map<String, String> r = child.values;
    String context = "child JVM output:\n" + child.output;
    System.out.println(context);

    assertEquals("true", r.get("split"), "the race was reproduced: ImageIO does not use getDefaultInstance()\n" + context);
    assertEquals("false", r.get("probeVisible"), "a reader registered through getDefaultInstance() only is invisible to ImageIO\n" + context);

    assertEquals("true", r.get("registered"), context);
    assertEquals(HeicImageReader.class.getName(), r.get("readerForHeic"), "what IfsUtil gets for a HEIC file\n" + context);
    if (Boolean.parseBoolean(r.get("decoderAvailable"))) {
      assertEquals("600x400", r.get("decoded"), context);
    }
    else {
      assertTrue(r.get("decoded").startsWith("IOException"), "fails like any HEIC file without a system decoder\n" + context);
    }
    assertEquals("1", r.get("inDefault"), "still registered in getDefaultInstance() as well\n" + context);
    assertEquals("true", r.get("visibleToImageIO"), context);
    assertEquals("true", r.get("warnedAboutSplit"), "the split is logged\n" + context);

    assertEquals("none", r.get("readerAfterUnregister"), "deregistered from ImageIO's registry too (plugin unload)\n" + context);
    assertEquals("0", r.get("inDefaultAfterUnregister"), context);
    assertEquals("true", r.get("registeredAgain"), "a reloaded plugin registers again\n" + context);
    assertEquals(HeicImageReader.class.getName(), r.get("readerAfterReload"), context);
  }

  private static final class Result {
    final Map<String, String> values;
    final String output;

    Result(Map<String, String> values, String output) {
      this.values = values;
      this.output = output;
    }
  }

  private static Result runChild() throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(ChildJvm.command(Child.class));
    builder.redirectErrorStream(true);
    Process process = builder.start();
    process.getOutputStream().close();
    byte[] out = process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child JVM timed out");
    String output = new String(out, StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    Map<String, String> values = new HashMap<>();
    for (String line : output.split("\n")) {
      if (!line.startsWith("RESULT ")) continue;
      int eq = line.indexOf('=');
      values.put(line.substring("RESULT ".length(), eq), line.substring(eq + 1).trim());
    }
    return new Result(values, output);
  }

  /** Runs in a fresh JVM. */
  public static final class Child {
    public static void main(String[] args) throws Exception {
      try (LogCapture log = new LogCapture()) { // before HeicSupport is initialized: it keeps its logger
        boolean split = forceSplitRegistry();
        out("split", split);

        // What the plugin did before the fix (and what the IDE's own WebP/SVG registrars still do).
        ProbeSpi probe = new ProbeSpi();
        IIORegistry.getDefaultInstance().registerServiceProvider(probe, ImageReaderSpi.class);
        out("probeVisible", ImageIO.getImageReadersByFormatName(ProbeSpi.FORMAT).hasNext());
        IIORegistry.getDefaultInstance().deregisterServiceProvider(probe, ImageReaderSpi.class);

        out("registered", HeicSupport.register());
        out("readerForHeic", readerFor("rgb_sips.heic"));
        out("decoderAvailable", HeifBackends.current().status().isAvailable());
        try {
          BufferedImage image = ImageIO.read(new ByteArrayInputStream(Fixtures.bytes("rgb_sips.heic")));
          out("decoded", image == null ? "null" : image.getWidth() + "x" + image.getHeight());
        }
        catch (IOException e) {
          out("decoded", "IOException: " + e.getMessage());
        }
        out("inDefault", countInDefault());
        out("visibleToImageIO", HeicSupport.isVisibleToImageIO());
        out("warnedAboutSplit", log.warnings().stream().anyMatch(w -> w.startsWith("ImageIO does not use IIORegistry.getDefaultInstance()")));
        for (String warning : log.warnings()) System.out.println("WARN " + warning);

        HeicSupport.unregister();
        out("readerAfterUnregister", readerFor("rgb_sips.heic"));
        out("inDefaultAfterUnregister", countInDefault());

        out("registeredAgain", HeicSupport.register());
        out("readerAfterReload", readerFor("rgb_sips.heic"));
        HeicSupport.unregister();
      }
      System.exit(0);
    }

    /**
     * Replays the startup race deterministically: thread A initializes ImageIO, which calls getDefaultInstance(),
     * finds no registry and builds one; before A stores it, thread B calls getDefaultInstance(), also finds none and
     * builds a second one; A stores its registry (ImageIO keeps it), then B stores its own (getDefaultInstance()
     * returns it from now on). Both threads are held inside {@code new IIORegistry()} through their context class
     * loader, which the constructor asks for META-INF/services files.
     */
    static boolean forceSplitRegistry() throws Exception {
      Gate imageIoGate = new Gate();
      Gate registrarGate = new Gate();
      Thread imageIoUser = new Thread(ImageIO::getReaderFormatNames, "splash (initializes ImageIO)");
      imageIoUser.setContextClassLoader(imageIoGate);
      Thread registrar = new Thread(IIORegistry::getDefaultInstance, "platform reader registrar");
      registrar.setContextClassLoader(registrarGate);

      imageIoUser.start();
      imageIoGate.awaitEntered();
      registrar.start();
      registrarGate.awaitEntered();
      imageIoGate.release();
      imageIoUser.join();
      registrarGate.release();
      registrar.join();

      ImageReader png = ImageIO.getImageReadersByFormatName("png").next();
      boolean split = !IIORegistry.getDefaultInstance().contains(png.getOriginatingProvider());
      png.dispose();
      return split;
    }

    /** A context class loader that blocks the first {@code getResources} call until released. */
    private static final class Gate extends ClassLoader {
      private final CountDownLatch entered = new CountDownLatch(1);
      private final CountDownLatch released = new CountDownLatch(1);

      Gate() {
        super(Child.class.getClassLoader());
      }

      @Override
      public Enumeration<URL> getResources(String name) {
        entered.countDown();
        try {
          if (!released.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Collections.emptyEnumeration();
      }

      void awaitEntered() throws InterruptedException {
        if (!entered.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("thread did not reach new IIORegistry()");
      }

      void release() {
        released.countDown();
      }
    }

    private static String readerFor(String fixture) throws IOException {
      try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(fixture)))) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        return readers.hasNext() ? readers.next().getClass().getName() : "none";
      }
    }

    private static int countInDefault() {
      int count = 0;
      Iterator<ImageReaderSpi> it = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, false);
      while (it.hasNext()) {
        if (it.next() instanceof HeicImageReaderSpi) count++;
      }
      return count;
    }

    private static void out(String key, Object value) {
      System.out.println("RESULT " + key + "=" + value);
    }
  }

  /** Stand-in for a reader registered the way the plugin used to (only through getDefaultInstance()). */
  private static final class ProbeSpi extends ImageReaderSpi {
    static final String FORMAT = "split-registry-probe";

    ProbeSpi() {
      super("probe", "1", new String[]{FORMAT}, null, null, "probe.Reader", new Class<?>[]{ImageInputStream.class},
            null, false, null, null, null, null, false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) {
      return false;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
      return null;
    }

    @Override
    public String getDescription(Locale locale) {
      return "probe";
    }
  }
}
