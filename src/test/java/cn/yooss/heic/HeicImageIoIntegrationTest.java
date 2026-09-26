package cn.yooss.heic;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real reader with the system decoder of this OS ({@code HeifBackends.current()}) driven through
 * {@code javax.imageio} exactly like the IDE does: {@code org.intellij.images.vfs.IfsUtil} (editor, diff) and
 * {@code org.intellij.images.util.ImageInfoReader} (image-info index, completion, documentation popup). Skipped where
 * no system decoder is available (HeifBackendContractTest checks that this matches what CI expects).
 */
@EnabledIf("cn.yooss.heic.SystemDecoder#isAvailable")
class HeicImageIoIntegrationTest {
  private static HeicImageReaderSpi spi;
  private static Map<String, String> readersBeforeRegistration;

  @BeforeAll
  static void register() throws IOException {
    readersBeforeRegistration = firstReaderPerFormat();
    spi = new HeicImageReaderSpi();
    IIORegistry.getDefaultInstance().registerServiceProvider(spi, ImageReaderSpi.class);
  }

  @AfterAll
  static void deregister() {
    IIORegistry.getDefaultInstance().deregisterServiceProvider(spi, ImageReaderSpi.class);
  }

  /** Replays {@code IfsUtil.refresh}: first reader by content, its format name, default param, full read. */
  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,      600, 400, 1",
      "rgb_libheif.heic,   600, 400, 1",
      "alpha_sips.heic,    400, 300, 2",
      "alpha_libheif.heic, 400, 300, 2",
      "rgb16_sips.heic,    512, 256, 1",
      "ten_bit.heic,       512, 256, 1",
      "exif3_apple.heic,   600, 400, 1",
      "exif5_apple.heic,   400, 600, 1",
      "exif6_apple.heic,   400, 600, 1",
      "rot90_irot.heic,    400, 600, 1",
      "fliph_imir.heic,    600, 400, 1",
      "grid_libheif.heic,  600, 400, 1",
      "multi.heic,         600, 400, 1",
      "seq.heics,          600, 400, 1",
      "bands_exif6.heic,  1200, 2000, 1",
  })
  void likeIfsUtil(String name, int width, int height, int type) throws IOException {
    byte[] content = Fixtures.bytes(name);
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      assertTrue(readers.hasNext(), "no reader for " + name);
      ImageReader reader = readers.next();
      assertInstanceOf(HeicImageReader.class, reader);
      try {
        assertEquals("heic", reader.getFormatName());
        ImageReadParam param = reader.getDefaultReadParam();
        reader.setInput(iis, true, true);
        BufferedImage image = reader.read(reader.getMinIndex(), param);
        assertEquals(width + "x" + height, image.getWidth() + "x" + image.getHeight());
        assertEquals(type, image.getType());
      }
      finally {
        reader.dispose();
      }
    }
  }

  /** Replays {@code ImageInfoReader.read}: no disk cache (length unknown), size and bit depth without a full decode. */
  @ParameterizedTest
  @CsvSource({
      "rgb_sips.heic,     600, 400, 24",
      "alpha_sips.heic,   400, 300, 32",
      "exif6_apple.heic,  400, 600, 24",
      "exif5_apple.heic,  400, 600, 24",
      "ten_bit.heic,      512, 256, 24",
      "grid_libheif.heic, 600, 400, 24",
      "seq.heics,         600, 400, 24",
  })
  void likeImageInfoReader(String name, int width, int height, int bitsPerPixel) throws IOException {
    boolean useCache = ImageIO.getUseCache();
    ImageIO.setUseCache(false);
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(name)))) {
      assertEquals(-1, iis.length(), "memory-cached stream of unknown length");
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      assertTrue(readers.hasNext());
      ImageReader reader = readers.next();
      try {
        reader.setInput(iis, true);
        assertEquals(width, reader.getWidth(0));
        assertEquals(height, reader.getHeight(0));
        assertEquals(bitsPerPixel, reader.getImageTypes(0).next().getColorModel().getPixelSize());
      }
      finally {
        reader.dispose();
      }
    }
    finally {
      ImageIO.setUseCache(useCache);
    }
  }

  @Test
  void otherFormatsKeepTheirReaders() throws IOException {
    Map<String, String> after = firstReaderPerFormat();
    assertEquals(readersBeforeRegistration, after);
    assertFalse(after.containsValue(HeicImageReader.class.getName()), after.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb.avif", "alpha.avif", "rgb_sips.avif", "garbage.heic"})
  void avifAndGarbageAreNotClaimed(String name) throws IOException {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(name)))) {
      ImageIO.getImageReaders(iis).forEachRemaining(r -> assertFalse(r instanceof HeicImageReader, name));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"garbage.heic", "header_only.heic"})
  void invalidHeicFailsWithIOException(String name) throws IOException {
    if (name.equals("garbage.heic")) {
      assertEquals(null, ImageIO.read(new ByteArrayInputStream(Fixtures.bytes(name))), "no reader at all");
    }
    else {
      assertThrows(IOException.class, () -> ImageIO.read(new ByteArrayInputStream(Fixtures.bytes(name))));
    }
  }

  @Test
  void truncatedHeicFailsWithIOException() {
    byte[] full = Fixtures.bytes("rgb_sips.heic");
    byte[] truncated = java.util.Arrays.copyOf(full, full.length - 100);
    assertThrows(IOException.class, () -> ImageIO.read(new ByteArrayInputStream(truncated)));
  }

  @Test
  void imageIoReadFileUrlAndStream(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("photo.heic");
    Files.write(file, Fixtures.bytes("exif6_apple.heic"));
    BufferedImage fromFile = ImageIO.read(file.toFile());
    assertNotNull(fromFile);
    assertEquals("400x600 TL=blue TR=red BL=white BR=green marker=TR", Fixtures.layout(fromFile));

    BufferedImage fromUrl = ImageIO.read(file.toUri().toURL()); // documentation popup fallback
    assertNotNull(fromUrl);
    assertEquals(400, fromUrl.getWidth());

    BufferedImage fromStream = ImageIO.read(Files.newInputStream(file));
    assertNotNull(fromStream);
    assertEquals(600, fromStream.getHeight());
  }

  @Test
  void decodedImageCanBeWrittenAsPng() throws IOException {
    BufferedImage alpha = ImageIO.read(new ByteArrayInputStream(Fixtures.bytes("alpha_sips.heic")));
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(alpha, "png", png));
    BufferedImage back = ImageIO.read(new ByteArrayInputStream(png.toByteArray()));
    double mean = Fixtures.meanDifference(back, Fixtures.png("alpha.png"));
    assertTrue(mean < 3.0, "straight alpha survives a PNG round trip, mean difference " + mean);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "2 | 2 | 300x200 TL=red TR=green BL=blue BR=white marker=TL",
      "4 | 4 | 150x100 TL=red TR=green BL=blue BR=white marker=TL",
      "1 | 4 | 600x100 TL=red TR=green BL=blue BR=white marker=TL",
      "3 | 1 | 200x400 TL=red TR=green BL=blue BR=white marker=TL",
  })
  void subsampling(int xSub, int ySub, String expected) throws IOException {
    ImageReadParam param = new ImageReadParam();
    param.setSourceSubsampling(xSub, ySub, 0, 0);
    assertEquals(expected, Fixtures.layout(readWith("rgb_sips.heic", param)));
  }

  @Test
  void subsamplingRoundsLikeImageIo() throws IOException {
    ImageReadParam param = new ImageReadParam();
    param.setSourceSubsampling(4, 4, 0, 0);
    BufferedImage image = readWith("alpha_sips.heic", param); // 400x300
    assertEquals("100x75", image.getWidth() + "x" + image.getHeight());
    assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
  }

  @Test
  void sourceRegion() throws IOException {
    ImageReadParam topLeft = new ImageReadParam();
    topLeft.setSourceRegion(new Rectangle(0, 0, 300, 200));
    BufferedImage red = readWith("rgb_sips.heic", topLeft);
    assertEquals("300x200", red.getWidth() + "x" + red.getHeight());
    assertEquals("red", Fixtures.colorName(red.getRGB(150, 100)));
    assertEquals("black", Fixtures.colorName(red.getRGB(10, 10)));

    // Regions are in display coordinates: after orientation 6 the top-left quadrant is blue.
    ImageReadParam rotated = new ImageReadParam();
    rotated.setSourceRegion(new Rectangle(0, 0, 200, 300));
    BufferedImage blue = readWith("exif6_apple.heic", rotated);
    assertEquals("200x300", blue.getWidth() + "x" + blue.getHeight());
    assertEquals("blue", Fixtures.colorName(blue.getRGB(100, 150)));

    ImageReadParam both = new ImageReadParam();
    both.setSourceRegion(new Rectangle(300, 200, 300, 200));
    both.setSourceSubsampling(2, 2, 0, 0);
    BufferedImage white = readWith("rgb_sips.heic", both);
    assertEquals("150x100", white.getWidth() + "x" + white.getHeight());
    assertEquals("white", Fixtures.colorName(white.getRGB(75, 50)));
  }

  /**
   * Like the IDE's viewer decodes PNG: every image at full size, even when the heap is full, as long as the decode needs
   * at most a quarter of the maximum heap (here 2 GB, completely in use), with the estimate of the system decoder and the
   * first-paint copy.
   */
  @ParameterizedTest
  @ValueSource(strings = {"rgb_sips.heic", "alpha_sips.heic", "rgb16_sips.heic", "exif6_apple.heic", "grid_libheif.heic",
                          "bands_2000x1200.heic", "bands_exif6.heic", "icc_wide.heic"})
  void ordinaryImagesDecodeAtFullSize(String name) throws IOException {
    HeicImageReaderSpi full = new HeicImageReaderSpi(null, new HeapValve(new HeapValveTest.FixedHeap(2L << 30, 2L << 30)));
    ImageReader reader = full.createReaderInstance();
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(name)))) {
      reader.setInput(iis, true, true);
      BufferedImage image = reader.read(0, reader.getDefaultReadParam());
      assertEquals(reader.getWidth(0) + "x" + reader.getHeight(0), image.getWidth() + "x" + image.getHeight());
    }
    finally {
      reader.dispose();
    }
    assertTrue(Downscales.isEmpty());
  }

  /**
   * The heap safety valve with the system decoder: a heap too full for the full-size estimate gets a smaller image, of
   * the same aspect ratio and colors, while getWidth/getHeight keep reporting the real size.
   */
  @Test
  void heapValveDownscalesWhenTheHeapIsShort() throws IOException {
    // 128 MB, 80 MB in use: the estimate of the 2000x1200 image (the image and its first-paint copy plus the fixed
    // part, about 43 MB) is more than a quarter of the heap, and the allowance min(51, 128 - 80 - 38) = 10 MB is less
    // than the fixed part alone: reduced to the smallest size, 1024 pixels.
    HeicImageReaderSpi small = new HeicImageReaderSpi(null, new HeapValve(new HeapValveTest.FixedHeap(128L << 20, 80L << 20)));
    ImageReader reader = small.createReaderInstance();
    byte[] data = Fixtures.bytes("bands_2000x1200.heic");
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
      reader.setInput(iis, true, true);
      assertEquals(2000, reader.getWidth(0), "getWidth reports the real size");
      BufferedImage image = reader.read(0, reader.getDefaultReadParam());
      assertEquals(1024, image.getWidth());
      assertTrue(Math.abs(image.getHeight() - 614) <= 1, "aspect ratio kept: " + image.getHeight());
      assertEquals(2000, reader.getWidth(0));
      assertEquals(1200, reader.getHeight(0));
      assertEquals("red", Fixtures.colorName(image.getRGB(512, 25)), "the first band");
      Downscales.Entry entry = Downscales.find(Downscales.key(data.length, Downscales.crc(data)));
      assertNotNull(entry);
      assertEquals("2000x1200 -> " + image.getWidth() + "x" + image.getHeight(),
                   entry.width() + "x" + entry.height() + " -> " + entry.shownWidth() + "x" + entry.shownHeight());
    }
    finally {
      reader.dispose();
      Downscales.clear();
    }
  }

  private static BufferedImage readWith(String name, ImageReadParam param) throws IOException {
    ImageReader reader = spi.createReaderInstance();
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(Fixtures.bytes(name)))) {
      reader.setInput(iis, true, true);
      return reader.read(0, param);
    }
    finally {
      reader.dispose();
    }
  }

  /** Class of the first reader ImageIO picks for common formats. */
  private static Map<String, String> firstReaderPerFormat() throws IOException {
    Map<String, byte[]> samples = new LinkedHashMap<>();
    samples.put("png", Fixtures.bytes("rgb.png"));
    samples.put("png-alpha", Fixtures.bytes("alpha.png"));
    samples.put("jpeg", HeicImageReaderSpiTest.jpeg());
    for (String format : new String[]{"gif", "bmp", "tiff"}) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      assertTrue(ImageIO.write(Fixtures.png("rgb.png"), format, out), format);
      samples.put(format, out.toByteArray());
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> sample : samples.entrySet()) {
      try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(sample.getValue()))) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        result.put(sample.getKey(), readers.hasNext() ? readers.next().getClass().getName() : "none");
      }
    }
    return result;
  }
}
