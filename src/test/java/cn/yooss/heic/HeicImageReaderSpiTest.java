package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifImageInfo;
import com.intellij.openapi.diagnostic.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPI and reader behaviour with fake decoding backends. Runs on every OS: proves that sniffing never touches the
 * native layer and that a broken native layer cannot break other image formats.
 */
class HeicImageReaderSpiTest {
  private final List<ImageReaderSpi> registered = new ArrayList<>();

  @AfterEach
  void deregister() {
    for (ImageReaderSpi spi : registered) {
      IIORegistry.getDefaultInstance().deregisterServiceProvider(spi, ImageReaderSpi.class);
    }
    registered.clear();
  }

  private HeicImageReaderSpi register(HeicImageReaderSpi spi) {
    IIORegistry.getDefaultInstance().registerServiceProvider(spi, ImageReaderSpi.class);
    registered.add(spi);
    return spi;
  }

  @Test
  void providerDescription() {
    HeicImageReaderSpi spi = new HeicImageReaderSpi();
    assertEquals("heic", spi.getFormatNames()[0], "names[0] is what the IDE shows as the format");
    assertArrayEquals(new String[]{"heic", "HEIC", "heif", "HEIF"}, spi.getFormatNames());
    assertArrayEquals(new String[]{"heic", "heif", "hif", "heics"}, spi.getFileSuffixes());
    assertTrue(Arrays.asList(spi.getMIMETypes()).containsAll(List.of("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")));
    assertArrayEquals(new Class<?>[]{ImageInputStream.class}, spi.getInputTypes());
    assertEquals(HeicImageReader.class.getName(), spi.getPluginClassName());
    assertNotNull(spi.getDescription(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb_sips.heic", "alpha_libheif.heic", "rgb16_sips.heic", "grid_libheif.heic", "seq.heics", "header_only.heic"})
  void sniffsHeifAndRestoresTheStreamPosition(String name) throws IOException {
    HeicImageReaderSpi spi = new HeicImageReaderSpi(() -> DecodeLimits.DEFAULT, FailingBackend.linkage());
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(name)))) {
      assertTrue(spi.canDecodeInput(stream));
      assertEquals(0, stream.getStreamPosition());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb.png", "alpha.png", "rgb.avif", "alpha.avif", "rgb_sips.avif", "garbage.heic"})
  void rejectsOtherFormats(String name) throws IOException {
    HeicImageReaderSpi spi = new HeicImageReaderSpi(() -> DecodeLimits.DEFAULT, FailingBackend.linkage());
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(name)))) {
      assertFalse(spi.canDecodeInput(stream));
      assertEquals(0, stream.getStreamPosition());
    }
  }

  @Test
  void rejectsNonStreamInputs() {
    HeicImageReaderSpi spi = new HeicImageReaderSpi();
    assertFalse(spi.canDecodeInput(new File("x.heic")));
    assertFalse(spi.canDecodeInput(Fixtures.bytes("rgb_sips.heic")));
    assertFalse(spi.canDecodeInput(null));
  }

  @Test
  void canDecodeInputNeverThrows() {
    HeicImageReaderSpi spi = new HeicImageReaderSpi();
    assertFalse(spi.canDecodeInput(new ThrowingStream(new IllegalStateException("boom"))));
    assertFalse(spi.canDecodeInput(new ThrowingStream(new OutOfMemoryError("simulated"))));
    assertFalse(spi.canDecodeInput(new ThrowingStream(new NoClassDefFoundError("simulated"))));
  }

  /** Failures swallowed by canDecodeInput are logged with their stack trace, once per cause (class + throwing frame). */
  @Test
  void canDecodeInputLogsSwallowedFailuresOncePerCause() {
    HeicImageReaderSpi spi = new HeicImageReaderSpi();
    try (LogCapture log = new LogCapture()) {
      assertFalse(spi.canDecodeInput(new ThrowingStream(sniffFailure("first"))));
      assertFalse(spi.canDecodeInput(new ThrowingStream(sniffFailure("same cause, other message"))));
      assertEquals(1, log.warnings().size(), log.warnings().toString());
      assertTrue(log.warnings().get(0).contains(SniffFailure.class.getName() + ": first"), log.warnings().toString());

      assertFalse(spi.canDecodeInput(new ThrowingStream(new OtherSniffFailure())));
      assertEquals(2, log.warnings().size(), "another cause is logged as well: " + log.warnings());
    }
  }

  @Test
  void brokenLoggingCannotBreakCanDecodeInput() {
    Logger.Factory previous = Logger.getFactory();
    Logger.setFactory(category -> {
      throw new IllegalStateException("logging is broken");
    });
    try {
      assertFalse(new HeicImageReaderSpi().canDecodeInput(new ThrowingStream(new UnloggedSniffFailure())));
    }
    finally {
      Logger.setFactory(previous);
    }
  }

  private static RuntimeException sniffFailure(String message) {
    return new SniffFailure(message); // one creation site = one cause
  }

  private static final class SniffFailure extends RuntimeException {
    SniffFailure(String message) {
      super(message);
    }
  }

  private static final class OtherSniffFailure extends RuntimeException {
  }

  private static final class UnloggedSniffFailure extends RuntimeException {
  }

  @Test
  void rejectsHugeStreams() throws IOException {
    byte[] heic = Fixtures.bytes("rgb_sips.heic");
    HeicImageReaderSpi spi = new HeicImageReaderSpi();
    try (ImageInputStream stream = new FixedLengthStream(heic, HeicImageReaderSpi.MAX_INPUT_BYTES + 1)) {
      assertFalse(spi.canDecodeInput(stream));
    }
    try (ImageInputStream stream = new FixedLengthStream(heic, HeicImageReaderSpi.MAX_INPUT_BYTES)) {
      assertTrue(spi.canDecodeInput(stream));
    }
  }

  /** The native layer failing to load must never affect other formats: canDecodeInput only sniffs bytes. */
  @ParameterizedTest
  @ValueSource(strings = {"linkage", "initializer", "runtime", "io"})
  void brokenNativeLayerDoesNotBreakOtherFormats(String failure) throws IOException {
    FailingBackend backend = FailingBackend.of(failure);
    register(new HeicImageReaderSpi(() -> DecodeLimits.DEFAULT, backend));

    BufferedImage png = ImageIO.read(new ByteArrayInputStream(Fixtures.bytes("rgb.png")));
    assertNotNull(png);
    assertEquals(600, png.getWidth());
    BufferedImage jpeg = ImageIO.read(new ByteArrayInputStream(jpeg()));
    assertNotNull(jpeg);
    assertEquals(0, backend.calls, "sniffing and reading other formats must not touch the backend");

    IOException error = assertThrows(IOException.class, () -> ImageIO.read(new ByteArrayInputStream(Fixtures.bytes("rgb_sips.heic"))));
    assertTrue(backend.calls > 0);
    if (!failure.equals("io")) assertNotNull(error.getCause(), "native failures are wrapped: " + error);
  }

  @Test
  void readerUsesTheLimitsSupplier() throws IOException {
    List<Integer> requested = new ArrayList<>();
    HeifBackend recording = new FakeBackend(600, 400, false) {
      @Override
      public BufferedImage decode(byte[] data, int maxPixelSize) {
        requested.add(maxPixelSize);
        return super.decode(data, maxPixelSize);
      }
    };
    HeicImageReaderSpi spi = new HeicImageReaderSpi(() -> new DecodeLimits(10_000, 16384), recording);
    BufferedImage image = read(spi, null);
    assertEquals(List.of(122), requested);
    assertEquals(122, Math.max(image.getWidth(), image.getHeight()));

    requested.clear();
    Supplier<DecodeLimits> throwing = () -> {
      throw new IllegalStateException("settings unavailable");
    };
    read(new HeicImageReaderSpi(throwing, recording), null);
    assertEquals(List.of(0), requested, "falls back to the default budget, which fits 600x400");
  }

  @Test
  void subsamplingAndRegionWithFakeBackend() throws IOException {
    HeicImageReaderSpi spi = new HeicImageReaderSpi(() -> DecodeLimits.DEFAULT, new FakeBackend(600, 400, false));

    ImageReadParam half = new ImageReadParam();
    half.setSourceSubsampling(2, 2, 0, 0);
    assertEquals("300x200 TL=red TR=green BL=blue BR=white marker=TL", Fixtures.layout(read(spi, half)));

    ImageReadParam odd = new ImageReadParam();
    odd.setSourceSubsampling(7, 3, 0, 0);
    BufferedImage oddImage = read(spi, odd);
    assertEquals((600 + 6) / 7 + "x" + (400 + 2) / 3, oddImage.getWidth() + "x" + oddImage.getHeight());

    ImageReadParam region = new ImageReadParam();
    region.setSourceRegion(new java.awt.Rectangle(300, 200, 300, 200));
    BufferedImage white = read(spi, region);
    assertEquals(300, white.getWidth());
    assertEquals("white", Fixtures.colorName(white.getRGB(150, 100)));

    ImageReadParam outside = new ImageReadParam();
    outside.setSourceRegion(new java.awt.Rectangle(1000, 1000, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> read(spi, outside));
  }

  @Test
  void imageTypes() throws IOException {
    assertEquals(24, pixelSize(new FakeBackend(10, 10, false)));
    assertEquals(32, pixelSize(new FakeBackend(10, 10, true)));
  }

  private static int pixelSize(HeifBackend backend) throws IOException {
    ImageReader reader = new HeicImageReaderSpi(() -> DecodeLimits.DEFAULT, backend).createReaderInstance();
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes("rgb_sips.heic")))) {
      reader.setInput(stream, true);
      return reader.getImageTypes(0).next().getColorModel().getPixelSize();
    }
    finally {
      reader.dispose();
    }
  }

  @Test
  void indexChecks() throws IOException {
    ImageReader reader = new HeicImageReaderSpi(() -> DecodeLimits.DEFAULT, new FakeBackend(10, 10, false)).createReaderInstance();
    assertThrows(IllegalStateException.class, () -> reader.getWidth(0), "no input");
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes("rgb_sips.heic")))) {
      reader.setInput(stream);
      assertEquals(1, reader.getNumImages(true));
      assertEquals(0, reader.getMinIndex());
      assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
      assertThrows(IndexOutOfBoundsException.class, () -> reader.read(1));
      assertEquals(10, reader.getWidth(0));
      assertSame(null, reader.getImageMetadata(0));
      assertSame(null, reader.getStreamMetadata());
    }
    finally {
      reader.dispose();
    }
  }

  private static BufferedImage read(HeicImageReaderSpi spi, ImageReadParam param) throws IOException {
    ImageReader reader = spi.createReaderInstance();
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes("rgb_sips.heic")))) {
      reader.setInput(stream, true, true);
      return reader.read(0, param);
    }
    finally {
      reader.dispose();
    }
  }

  static byte[] jpeg() throws IOException {
    BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.ORANGE);
    g.fillRect(0, 0, 64, 48);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "jpg", out));
    return out.toByteArray();
  }

  /** A backend without native code; decodeThumbnail delegates to decode. */
  abstract static class TestBackend implements HeifBackend {
    @Override
    public String id() {
      return "test";
    }

    @Override
    public String displayName() {
      return "test backend";
    }

    @Override
    public HeifBackendStatus status() {
      return HeifBackendStatus.available("test");
    }

    @Override
    public HeifBackendStatus recheckStatus() {
      return status();
    }

    @Override
    public BufferedImage decodeThumbnail(byte[] data, int maxPixelSize) throws IOException {
      return decode(data, maxPixelSize);
    }
  }

  /** Renders the 600x400 quadrant layout at the size the reader asks for, like a system decoder would. */
  static class FakeBackend extends TestBackend {
    private final int width, height;
    private final boolean alpha;

    FakeBackend(int width, int height, boolean alpha) {
      this.width = width;
      this.height = height;
      this.alpha = alpha;
    }

    @Override
    public HeifImageInfo readInfo(byte[] data) {
      return new HeifImageInfo("public.heic", 1, 0, width, height, 1, 8, alpha);
    }

    @Override
    public BufferedImage decode(byte[] data, int maxPixelSize) {
      double scale = maxPixelSize <= 0 ? 1 : Math.min(1, (double) maxPixelSize / Math.max(width, height));
      int w = Math.max(1, (int) Math.round(width * scale)), h = Math.max(1, (int) Math.round(height * scale));
      BufferedImage image = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setColor(Color.RED);
      g.fillRect(0, 0, w / 2, h / 2);
      g.setColor(Color.GREEN);
      g.fillRect(w / 2, 0, w - w / 2, h / 2);
      g.setColor(Color.BLUE);
      g.fillRect(0, h / 2, w / 2, h - h / 2);
      g.setColor(Color.WHITE);
      g.fillRect(w / 2, h / 2, w - w / 2, h - h / 2);
      g.setColor(Color.BLACK);
      g.fillRect(0, 0, Math.max(1, (int) (40 * scale)), Math.max(1, (int) (40 * scale)));
      g.dispose();
      return image;
    }
  }

  static final class FailingBackend extends TestBackend {
    private final Supplier<Throwable> failure;
    int calls;

    private FailingBackend(Supplier<Throwable> failure) {
      this.failure = failure;
    }

    static FailingBackend linkage() {
      return of("linkage");
    }

    static FailingBackend of(String kind) {
      return new FailingBackend(switch (kind) {
        case "linkage" -> () -> new UnsatisfiedLinkError("Cannot open library: ImageIO");
        case "initializer" -> () -> new ExceptionInInitializerError(new IllegalArgumentException("Cannot open library"));
        case "runtime" -> () -> new IllegalStateException("native call failed");
        case "io" -> () -> new IOException("decoder error");
        default -> throw new IllegalArgumentException(kind);
      });
    }

    @Override
    public HeifImageInfo readInfo(byte[] data) throws IOException {
      throw fail();
    }

    @Override
    public BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
      throw fail();
    }

    private IOException fail() {
      calls++;
      Throwable t = failure.get();
      if (t instanceof IOException e) return e;
      if (t instanceof RuntimeException e) throw e;
      throw (Error) t;
    }
  }

  /** A stream whose reads fail with an arbitrary throwable. */
  private static final class ThrowingStream extends ImageInputStreamImpl {
    private final Throwable failure;

    ThrowingStream(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public int read() {
      return throwIt();
    }

    @Override
    public int read(byte[] b, int off, int len) {
      return throwIt();
    }

    private int throwIt() {
      if (failure instanceof RuntimeException e) throw e;
      throw (Error) failure;
    }
  }

  /** In-memory stream that reports an arbitrary length. */
  private static final class FixedLengthStream extends ImageInputStreamImpl {
    private final byte[] data;
    private final long length;

    FixedLengthStream(byte[] data, long length) {
      this.data = data;
      this.length = length;
    }

    @Override
    public long length() {
      return length;
    }

    @Override
    public int read() {
      if (streamPos >= data.length) return -1;
      return data[(int) streamPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (streamPos >= data.length) return -1;
      int n = (int) Math.min(len, data.length - streamPos);
      System.arraycopy(data, (int) streamPos, b, off, n);
      streamPos += n;
      return n;
    }
  }

  @Test
  void readerIsOurs() {
    assertInstanceOf(HeicImageReader.class, new HeicImageReaderSpi().createReaderInstance(null));
  }
}
