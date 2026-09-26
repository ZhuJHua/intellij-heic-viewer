package cn.yooss.heic.win;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.backend.PixelPipeline;
import cn.yooss.heic.win.jna.JnaWinApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real COM pipeline of the Windows backend (JNA, vtable calls, WIC, Media Foundation), also on runners without the
 * HEIF Image Extension: WIC's built-in PNG, BMP, TIFF, GIF and JPEG decoders go through exactly the code that decodes
 * HEIC ({@link WicDecoder} with the HEIF-only check switched off, a test-only parameter), and the results are compared
 * with Java's ImageIO. HEIC itself is covered by {@code HeifBackendContractTest} where the extensions are installed.
 */
@EnabledOnOs(OS.WINDOWS)
class WicNativeTest {
  private static WicDecoder decoder;

  @BeforeAll
  static void bind() {
    decoder = new WicDecoder(new JnaWinApi());
    System.out.println("WIC binding: " + decoder.api().name());
  }

  private static byte[] encode(BufferedImage image, String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, format, out), format);
    return out.toByteArray();
  }

  private static BufferedImage read(byte[] data) throws IOException {
    return ImageIO.read(new ByteArrayInputStream(data));
  }

  private static int[] rgb(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  /** Lossless formats decode to exactly what ImageIO decodes, in any strip size. */
  @ParameterizedTest
  @ValueSource(strings = {"png", "bmp", "tiff", "gif"})
  void losslessFormatsMatchImageIo(String format) throws IOException {
    byte[] data = encode(Fixtures.png("rgb.png"), format);
    BufferedImage expected = read(data);
    for (int stripPixels : new int[]{PixelPipeline.STRIP_PIXELS, 600 * 7, 599}) {
      BufferedImage image = decoder.decode(data, 0, false, stripPixels);
      assertEquals(BufferedImage.TYPE_INT_RGB, image.getType(), format);
      assertArrayEquals(rgb(expected), rgb(image), format + " in strips of " + stripPixels + " pixels");
    }
  }

  @Test
  void jpegIsCloseToImageIo() throws IOException {
    byte[] data = encode(Fixtures.png("rgb.png"), "jpeg");
    BufferedImage image = decoder.decode(data, 0, false, PixelPipeline.STRIP_PIXELS);
    double mean = Fixtures.meanDifference(image, read(data));
    assertTrue(mean < 2.0, "mean difference " + mean);
    assertEquals("600x400 TL=red TR=green BL=blue BR=white marker=TL", Fixtures.layout(image));
  }

  /** PNG with alpha: WIC's frame is 32bppBGRA (straight), so the image has alpha and exactly ImageIO's values. */
  @Test
  void pngWithAlpha() throws IOException {
    byte[] data = Fixtures.bytes("alpha.png");
    BufferedImage image = decoder.decode(data, 0, false, 12345);
    assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
    assertArrayEquals(rgb(read(data)), rgb(image));
    assertTrue(decoder.readInfo(data, false).hasAlpha());
    assertFalse(decoder.readInfo(Fixtures.bytes("rgb.png"), false).hasAlpha());
  }

  /** IWICBitmapScaler (Fant) for a smaller decode. */
  @Test
  void downscaled() throws IOException {
    byte[] data = Fixtures.bytes("bands.png");
    BufferedImage source = read(data);
    BufferedImage image = decoder.decode(data, 500, false, 777);
    int[] expected = WicDecoder.targetSize(source.getWidth(), source.getHeight(), 500);
    assertEquals(expected[0] + "x" + expected[1], image.getWidth() + "x" + image.getHeight());
    double mean = Fixtures.meanDifference(image, PixelPipeline.downscale(source, 500));
    assertTrue(mean < 6.0, "mean difference to Java's downscaling " + mean);
    BufferedImage small = decoder.decode(Fixtures.bytes("rgb.png"), 64, false, PixelPipeline.STRIP_PIXELS);
    assertEquals("64x43", small.getWidth() + "x" + small.getHeight());
  }

  /** {@code System.Photo.Orientation} (here from the EXIF of a JPEG) is read and applied. */
  @Test
  void orientationFromMetadataIsApplied() throws IOException {
    byte[] jpeg = withExifOrientation(encode(Fixtures.png("rgb.png"), "jpeg"), 6);
    assertEquals(6, decoder.readInfo(jpeg, false).orientation());
    BufferedImage image = decoder.decode(jpeg, 0, false, PixelPipeline.STRIP_PIXELS);
    assertEquals("400x600 TL=blue TR=red BL=white BR=green marker=TR", Fixtures.layout(image));
    BufferedImage small = decoder.decode(jpeg, 150, false, PixelPipeline.STRIP_PIXELS);
    assertEquals("100x150", small.getWidth() + "x" + small.getHeight());
    assertEquals(1, decoder.readInfo(Fixtures.bytes("rgb.png"), false).orientation());
  }

  /** An embedded ICC profile (here the iCCP chunk of a PNG) comes back as a color context and is converted to sRGB. */
  @Test
  void iccProfileIsConvertedToSrgb() throws IOException {
    BufferedImage gradient = new BufferedImage(256, 64, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++) {
      for (int x = 0; x < 256; x++) gradient.setRGB(x, y, x << 16 | (255 - x) << 8 | (y * 4));
    }
    byte[] linear = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
    byte[] png = withIccProfile(encode(gradient, "png"), linear);
    BufferedImage expected = new BufferedImage(256, 64, BufferedImage.TYPE_INT_RGB);
    expected.setRGB(0, 0, 256, 64, rgb(gradient), 0, 256);
    PixelPipeline.convertToSrgb(expected, linear);
    double converted = Fixtures.meanDifference(gradient, expected);
    assertTrue(converted > 3, "the profile makes a difference: " + converted);
    BufferedImage image = decoder.decode(png, 0, false, PixelPipeline.STRIP_PIXELS);
    double mean = Fixtures.meanDifference(image, expected);
    assertTrue(mean < 0.5, "mean difference " + mean);
  }

  /** COM on threads that already joined an apartment: an STA (like AWT threads) and the MTA. */
  @Test
  void threadsInEitherApartment() throws Exception {
    byte[] data = Fixtures.bytes("rgb.png");
    int[] expected = rgb(read(data));
    for (int coInit : new int[]{WicDecoder.COINIT_MULTITHREADED, 0x2 /* COINIT_APARTMENTTHREADED */}) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread thread = new Thread(() -> {
        WinApi api = decoder.api();
        int hr = api.coInitializeEx(coInit);
        try {
          assertEquals(Hresult.S_OK, hr, Hresult.describe(hr));
          assertArrayEquals(expected, rgb(decoder.decode(data, 0, false, PixelPipeline.STRIP_PIXELS)));
          assertArrayEquals(expected, rgb(decoder.decode(data, 0, false, PixelPipeline.STRIP_PIXELS)));
        }
        catch (Throwable t) {
          failure.set(t);
        }
        finally {
          if (Hresult.succeeded(hr)) api.coUninitialize();
        }
      });
      thread.start();
      thread.join(60_000);
      if (failure.get() != null) throw new AssertionError("apartment " + coInit, failure.get());
    }
  }

  @Test
  void onlyHeifUnlessTestsAskOtherwise() {
    IOException e = assertThrows(IOException.class, () -> decoder.decode(Fixtures.bytes("rgb.png"), 0, true, 1000));
    assertTrue(e.getMessage().contains("did not read the data as HEIF"), e.getMessage());
    WicException unknown = assertThrows(WicException.class, () -> decoder.decode("not an image".getBytes(), 0, false, 1000));
    assertTrue(unknown.getMessage().startsWith("IWICImagingFactory::CreateDecoderFromStream failed"), unknown.getMessage());
  }

  /** MFTEnumEx takes its category GUID by value (a pointer on x64, two registers on arm64): it must not crash. */
  @Test
  void mediaFoundationEnumeration() {
    List<String> h264 = new ArrayList<>();
    int hr = decoder.api().enumerateVideoDecoders(Guids.MFVideoFormat_H264, WicProbe.MFT_ENUM_FLAGS, h264);
    System.out.println("H.264 decoders: " + Hresult.describe(hr) + " " + h264);
    assertTrue(Hresult.succeeded(hr), Hresult.describe(hr));
    for (String name : h264) assertFalse(name.isEmpty());
    List<String> hevc = new ArrayList<>();
    assertTrue(Hresult.succeeded(decoder.api().enumerateVideoDecoders(Guids.MFVideoFormat_HEVC, WicProbe.MFT_ENUM_FLAGS, hevc)));
    System.out.println("HEVC decoders: " + hevc);
    if (HeifBackends.current().status().isAvailable()) assertFalse(hevc.isEmpty(), "HEIC decodes, so there is an HEVC decoder");
  }

  /** The backend of this runner, whatever it has installed: its status and what decoding a HEIC then does. */
  @Test
  void heicOnThisRunner() throws IOException {
    HeifBackend backend = HeifBackends.current();
    assertInstanceOf(WicHeifBackend.class, backend);
    HeifBackendStatus status = backend.status();
    System.out.println("Windows backend: " + status);
    byte[] heic = Fixtures.bytes("rgb_sips.heic");
    if (status.isAvailable()) {
      assertEquals("600x400 TL=red TR=green BL=blue BR=white marker=TL", Fixtures.layout(backend.decode(heic, 0)));
      return;
    }
    assertTrue(status.isUserInstallable() || status.reason() == HeifBackendStatus.Reason.ERROR, status.toString());
    IOException e = assertThrows(IOException.class, () -> backend.decode(heic, 0));
    assertTrue(e.getMessage().contains(String.valueOf(status.reason())), e.getMessage());
    if (status.reason() == HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING) {
      WicException direct = assertThrows(WicException.class, () -> decoder.decode(heic, 0, true, 1000));
      assertTrue(WicDecoder.isHeifDecoderMissing(direct.hresult()), direct.getMessage());
      assertTrue(direct.getMessage().contains("HEIF Image Extension"), direct.getMessage());
    }
    if (status.reason() == HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING) {
      cn.yooss.heic.backend.HeifImageInfo info = decoder.readInfo(heic, true);
      assertEquals("600x400", info.width() + "x" + info.height(), "the container is readable without HEVC");
    }
  }

  /** Where the extensions are installed: the embedded probe sample, alpha and orientation through the real decoder. */
  @Test
  void heicWithTheExtensions() throws IOException {
    assumeTrue(HeifBackends.current().status().isAvailable(), "no HEIF Image Extension / HEVC Video Extension");
    assertNull(WicProbe.checkSample(decoder.decode(WicProbe.sample(), 0, true, 100)));
    assertTrue(decoder.readInfo(Fixtures.bytes("alpha_sips.heic"), true).hasAlpha());
    assertFalse(decoder.readInfo(Fixtures.bytes("rgb_sips.heic"), true).hasAlpha());
    assertEquals(1, decoder.readInfo(Fixtures.bytes("exif6_apple.heic"), true).orientation(), "the decoder applies irot");
    BufferedImage alpha = decoder.decode(Fixtures.bytes("alpha_libheif.heic"), 100, true, 999);
    assertEquals("100x75", alpha.getWidth() + "x" + alpha.getHeight());
    assertEquals(BufferedImage.TYPE_INT_ARGB, alpha.getType());
    assertEquals(0, alpha.getRGB(12, 37) >>> 24, "transparent column");
    assertTrue(Math.abs((alpha.getRGB(62, 37) >>> 24) - 255) <= 3, "opaque column");
  }

  // ---------------------------------------------------------------- test data
  /** Inserts an APP1 Exif segment with {@code Orientation} right after the SOI marker of a JPEG. */
  static byte[] withExifOrientation(byte[] jpeg, int orientation) {
    byte[] exif = {
      (byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
      'I', 'I', 42, 0, 8, 0, 0, 0,                        // TIFF header, little-endian, IFD at 8
      1, 0,                                               // one entry
      0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, // 274 Orientation, SHORT, 1 value
      0, 0, 0, 0};                                        // no next IFD
    byte[] result = new byte[jpeg.length + exif.length];
    System.arraycopy(jpeg, 0, result, 0, 2);
    System.arraycopy(exif, 0, result, 2, exif.length);
    System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
    return result;
  }

  /** Inserts an iCCP chunk (the profile, deflated) right after the IHDR chunk of a PNG. */
  static byte[] withIccProfile(byte[] png, byte[] profile) {
    Deflater deflater = new Deflater();
    deflater.setInput(profile);
    deflater.finish();
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer));
    deflater.end();
    ByteArrayOutputStream chunk = new ByteArrayOutputStream();
    byte[] name = "linear\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    byte[] data = new byte[name.length + 1 + compressed.size()];
    System.arraycopy(name, 0, data, 0, name.length);
    data[name.length] = 0; // compression method: deflate
    System.arraycopy(compressed.toByteArray(), 0, data, name.length + 1, compressed.size());
    writeInt(chunk, data.length);
    byte[] typeAndData = new byte[4 + data.length];
    System.arraycopy("iCCP".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, typeAndData, 0, 4);
    System.arraycopy(data, 0, typeAndData, 4, data.length);
    chunk.write(typeAndData, 0, typeAndData.length);
    CRC32 crc = new CRC32();
    crc.update(typeAndData);
    writeInt(chunk, (int) crc.getValue());
    int afterIhdr = 8 + 8 + 13 + 4; // signature, IHDR length + type, IHDR data, CRC
    byte[] result = new byte[png.length + chunk.size()];
    System.arraycopy(png, 0, result, 0, afterIhdr);
    System.arraycopy(chunk.toByteArray(), 0, result, afterIhdr, chunk.size());
    System.arraycopy(png, afterIhdr, result, afterIhdr + chunk.size(), png.length - afterIhdr);
    return result;
  }

  private static void writeInt(ByteArrayOutputStream out, int value) {
    out.write(value >>> 24);
    out.write(value >>> 16);
    out.write(value >>> 8);
    out.write(value);
  }
}
