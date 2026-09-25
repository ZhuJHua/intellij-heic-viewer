package cn.yooss.heic.win;

import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.PixelPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WicDecoder} against {@link FakeWinApi}, on every OS: the pixel path, alpha, scaling, orientation, color
 * profiles, COM initialization and, above all, that every COM object is released, every buffer freed and COM
 * uninitialized on every path, including a failure of each single call.
 */
class WicDecoderTest {
  private static final byte[] DATA = {1, 2, 3};

  /** 4x3 with a distinct color per pixel. */
  private static FakeWinApi fake() {
    FakeWinApi api = new FakeWinApi();
    int[] pixels = new int[12];
    for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFF000000 | (i * 20) << 16 | (255 - i * 20) << 8 | (i * 7);
    api.setImage(4, 3, pixels);
    return api;
  }

  private static int[] rgb(BufferedImage image) {
    return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
  }

  @Test
  void opaqueImage() throws IOException {
    FakeWinApi api = fake();
    BufferedImage image = new WicDecoder(api).decode(DATA, 0, true, PixelPipeline.STRIP_PIXELS);
    assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
    assertArrayEquals(api.pixels, rgb(image));
    int[] raw = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    for (int p : raw) assertEquals(0, p >>> 24, "the undefined fourth byte of 32bppBGR is cleared");
    assertFalse(api.calls.contains("CreateBitmapScaler"), "no scaler at full size");
    api.assertClean();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 4, 5, 8, 11, 12, 1 << 20})
  void stripsOfAnySize(int stripPixels) throws IOException {
    FakeWinApi api = fake();
    assertArrayEquals(api.pixels, rgb(new WicDecoder(api).decode(DATA, 0, true, stripPixels)));
    api.assertClean();
  }

  @Test
  void alphaPlaneOfTheHeifDecoderIsMergedAsStraightAlpha() throws IOException {
    FakeWinApi api = fake();
    api.alphaPlane = new byte[12];
    for (int i = 0; i < 12; i++) api.alphaPlane[i] = (byte) (i * 21);
    BufferedImage image = new WicDecoder(api).decode(DATA, 0, true, 5);
    assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
    assertFalse(image.isAlphaPremultiplied());
    int[] raw = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    for (int i = 0; i < 12; i++) assertEquals((i * 21) << 24 | (api.pixels[i] & 0xFFFFFF), raw[i], "pixel " + i);
    assertTrue(new WicDecoder(api).readInfo(DATA, true).hasAlpha());
    api.assertClean();
  }

  @Test
  void otherFormatsWithAlphaKeepTheirAlpha() throws IOException {
    FakeWinApi api = fake();
    api.containerFormat = Guids.GUID_ContainerFormatPng;
    api.framePixelFormat = Guids.GUID_WICPixelFormat32bppBGRA;
    api.pixels[5] = 0x40123456;
    BufferedImage image = new WicDecoder(api).decode(DATA, 0, false, 7);
    assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
    assertEquals(0x40123456, ((DataBufferInt) image.getRaster().getDataBuffer()).getData()[5]);
    api.assertClean();
  }

  /** A HEIF decoder whose frames carry alpha themselves (not the one of HEIF Image Extension 1.2.36) keeps it too. */
  @Test
  void heifFramesWithAlphaInThePixels() throws IOException {
    FakeWinApi api = fake();
    api.framePixelFormat = Guids.GUID_WICPixelFormat32bppBGRA;
    api.pixels[3] = 0x20ABCDEF;
    BufferedImage image = new WicDecoder(api).decode(DATA, 0, true, 7);
    assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
    assertEquals(0x20ABCDEF, ((DataBufferInt) image.getRaster().getDataBuffer()).getData()[3]);
    api.assertClean();
  }

  @Test
  void heifOnlyUnlessATestAsksOtherwise() {
    FakeWinApi api = fake();
    api.containerFormat = Guids.GUID_ContainerFormatPng;
    IOException e = assertThrows(IOException.class, () -> new WicDecoder(api).decode(DATA, 0, true, 7));
    assertTrue(e.getMessage().contains("did not read the data as HEIF"), e.getMessage());
    api.assertClean();
  }

  @Test
  void downscaledWithTheScaler() throws IOException {
    FakeWinApi api = fake();
    api.setImage(40, 20, new int[800]);
    BufferedImage image = new WicDecoder(api).decode(DATA, 10, true, 7);
    assertEquals("10x5", image.getWidth() + "x" + image.getHeight());
    assertTrue(api.calls.contains("CreateBitmapScaler") && api.calls.contains("IWICBitmapScaler::Initialize"));
    api.assertClean();
  }

  @Test
  void targetSizeLikePixelPipeline() {
    assertArrayEquals(new int[]{600, 400}, WicDecoder.targetSize(600, 400, 0));
    assertArrayEquals(new int[]{600, 400}, WicDecoder.targetSize(600, 400, 600));
    assertArrayEquals(new int[]{600, 400}, WicDecoder.targetSize(600, 400, 5000));
    assertArrayEquals(new int[]{150, 100}, WicDecoder.targetSize(600, 400, 150));
    assertArrayEquals(new int[]{100, 150}, WicDecoder.targetSize(400, 600, 150));
    assertArrayEquals(new int[]{64, 1}, WicDecoder.targetSize(10000, 10, 64));
    assertArrayEquals(new int[]{1, 64}, WicDecoder.targetSize(10, 10000, 64));
  }

  @Test
  void reportedOrientationIsApplied() throws IOException {
    FakeWinApi api = fake();
    api.orientation = 6; // rotate 90 degrees clockwise
    BufferedImage image = new WicDecoder(api).decode(DATA, 0, true, 7);
    assertEquals("3x4", image.getWidth() + "x" + image.getHeight());
    // the top-left pixel of the display is the bottom-left pixel of the stored image
    assertEquals(api.pixels[2 * 4], image.getRGB(0, 0));
    HeifImageInfo info = new WicDecoder(api).readInfo(DATA, true);
    assertEquals(6, info.orientation());
    assertEquals("3x4", info.width() + "x" + info.height());
    api.assertClean();
  }

  @Test
  void noMetadataMeansNoOrientation() throws IOException {
    FakeWinApi api = fake();
    api.orientation = 0;
    assertEquals(1, new WicDecoder(api).readInfo(DATA, true).orientation());
    api.orientation = 9; // invalid
    assertEquals(1, new WicDecoder(api).readInfo(DATA, true).orientation());
    api.assertClean();
  }

  @Test
  void readInfo() throws IOException {
    FakeWinApi api = fake();
    api.frameCount = 3;
    byte[] heic = cn.yooss.heic.Fixtures.bytes("rgb_sips.heic");
    HeifImageInfo info = new WicDecoder(api).readInfo(heic, true);
    assertEquals("heic", info.typeIdentifier());
    assertEquals(3, info.imageCount());
    assertEquals("4x3", info.width() + "x" + info.height());
    assertFalse(info.hasAlpha());
    assertFalse(api.calls.contains("CopyPixels"), "no pixels decoded");
    api.assertClean();
  }

  @Test
  void iccProfileIsConvertedToSrgb() throws IOException {
    FakeWinApi api = fake();
    byte[] linear = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
    api.iccProfile = linear;
    api.exifColorSpace = 1; // an EXIF context first, as some decoders report both
    BufferedImage image = new WicDecoder(api).decode(DATA, 0, true, 7);
    BufferedImage expected = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
    expected.setRGB(0, 0, 4, 3, api.pixels, 0, 4);
    PixelPipeline.convertToSrgb(expected, linear);
    assertArrayEquals(rgb(expected), rgb(image));
    assertNotEquals(api.pixels[5], image.getRGB(1, 1), "linear RGB is brighter in sRGB");
    api.assertClean();
  }

  @Test
  void srgbProfileAndExifColorSpaceNeedNoConversion() throws IOException {
    FakeWinApi api = fake();
    api.iccProfile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
    assertArrayEquals(api.pixels, rgb(new WicDecoder(api).decode(DATA, 0, true, 7)));
    api.iccProfile = null;
    api.exifColorSpace = 1;
    assertArrayEquals(api.pixels, rgb(new WicDecoder(api).decode(DATA, 0, true, 7)));
    api.assertClean();
  }

  /** CoInitializeEx: S_OK and S_FALSE are balanced by CoUninitialize, RPC_E_CHANGED_MODE is not, errors fail. */
  @Test
  void comInitialization() throws IOException {
    for (int result : new int[]{Hresult.S_OK, Hresult.S_FALSE, Hresult.RPC_E_CHANGED_MODE}) {
      FakeWinApi api = fake();
      api.coInitializeResult = result;
      new WicDecoder(api).decode(DATA, 0, true, 7);
      new WicDecoder(api).readInfo(DATA, true);
      assertEquals(2, api.coInitializeCalls);
      assertEquals(result == Hresult.RPC_E_CHANGED_MODE ? 0 : 2, api.coUninitializeCalls, Hresult.describe(result));
      if (result != Hresult.RPC_E_CHANGED_MODE) {
        assertEquals("CoUninitialize", api.calls.get(api.calls.size() - 1), "uninitialized last, after every Release");
      }
      api.assertClean();
    }
    FakeWinApi api = fake();
    api.coInitializeResult = Hresult.E_OUTOFMEMORY;
    WicException e = assertThrows(WicException.class, () -> new WicDecoder(api).decode(DATA, 0, true, 7));
    assertEquals(Hresult.E_OUTOFMEMORY, e.hresult());
    assertEquals(0, api.coUninitializeCalls);
    api.assertClean();
  }

  /** Every single call may fail: the result is an IOException and nothing leaks. */
  @ParameterizedTest
  @ValueSource(strings = {"CoCreateInstance", "SHCreateMemStream", "CreateDecoderFromStream", "GetContainerFormat",
      "GetFrameCount", "GetFrame", "GetSize", "GetPixelFormat", "CreateBitmapScaler", "IWICBitmapScaler::Initialize",
      "CreateFormatConverter", "IWICFormatConverter::Initialize", "CreateBitmapFromSource", "CopyPixels", "malloc",
      "IWICBitmapSourceTransform::CopyPixels"})
  void everyFailureIsAnIOExceptionAndReleasesEverything(String method) {
    FakeWinApi api = fake();
    api.setImage(40, 20, new int[800]);
    api.alphaPlane = new byte[800];
    api.failures.put(method, Hresult.E_FAIL);
    IOException e = assertThrows(IOException.class, () -> new WicDecoder(api).decode(DATA, 10, true, 7));
    if (!method.equals("SHCreateMemStream") && !method.equals("malloc")) {
      WicException wic = assertInstanceOf(WicException.class, e);
      assertEquals(Hresult.E_FAIL, wic.hresult());
      assertTrue(e.getMessage().contains("0x80004005 (E_FAIL)"), e.getMessage());
    }
    api.assertClean();
  }

  /** Optional information that fails is left out, but the image is still decoded. */
  @ParameterizedTest
  @ValueSource(strings = {"GetMetadataQueryReader", "GetMetadataByName", "GetColorContexts", "CreateColorContext",
      "GetProfileBytes", "IWICColorContext::GetType", "QueryInterface", "GetClosestPixelFormat", "GetClosestSize",
      "CreateComponentInfo", "SupportsTransparency"})
  void optionalCallsMayFail(String method) throws IOException {
    FakeWinApi api = fake();
    api.iccProfile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
    api.failures.put(method, Hresult.E_FAIL);
    BufferedImage image = new WicDecoder(api).decode(DATA, 0, true, 7);
    assertEquals("4x3", image.getWidth() + "x" + image.getHeight());
    assertArrayEquals(api.pixels, rgb(image));
    api.assertClean();
  }

  @Test
  void heifDecoderMissing() {
    FakeWinApi api = fake();
    api.failures.put("CreateDecoderFromStream", Hresult.WINCODEC_ERR_COMPONENTINITIALIZEFAILURE);
    WicException e = assertThrows(WicException.class, () -> new WicDecoder(api).decode(DATA, 0, true, 7));
    assertTrue(e.getMessage().contains("HEIF Image Extension"), e.getMessage());
    assertTrue(e.getMessage().contains("WINCODEC_ERR_COMPONENTINITIALIZEFAILURE"), e.getMessage());
    api.assertClean();
  }

  /** HEIF data goes to WIC with the color workarounds: a rewritten copy for a single 8-bit image (macOS: both). */
  @Test
  void colorFixesRewriteTheData() throws IOException {
    byte[] heic = cn.yooss.heic.Fixtures.bytes("rgb_sips.heic"); // single 8-bit image, nclx (2, 2, 6, 1)
    FakeWinApi api = fake();
    new WicDecoder(api, true).decode(heic, 0, true, 7);
    assertEquals(1, api.streams.size());
    byte[] expected = SingleImageGrid.wrap(heic);
    assertNotNull(expected);
    assertTrue(NclxTransfer.asSrgbInPlace(expected));
    assertArrayEquals(expected, api.streams.get(0));
    api.assertClean();

    FakeWinApi off = fake();
    new WicDecoder(off, false).decode(heic, 0, true, 7);
    assertArrayEquals(heic, off.streams.get(0), "switched off: the file as it is");
    FakeWinApi grid = fake();
    byte[] gridHeic = cn.yooss.heic.Fixtures.bytes("grid_libheif.heic"); // grid, nclx (1, 13, 6, 1): nothing to fix
    new WicDecoder(grid, true).decode(gridHeic, 0, true, 7);
    assertArrayEquals(gridHeic, grid.streams.get(0));
    FakeWinApi png = fake();
    new WicDecoder(png, true).decode(heic, 0, false, 7);
    assertArrayEquals(heic, png.streams.get(0), "only HEIF decodes are rewritten");
    new WicDecoder(png, true).readInfo(heic, true);
    assertArrayEquals(heic, png.streams.get(1), "readInfo reads the file as it is");
  }

  /** If WIC does not take the rewritten data, the file is decoded as it is; its failure is the one reported. */
  @Test
  void rejectedRewriteFallsBackToTheFile() throws IOException {
    byte[] heic = cn.yooss.heic.Fixtures.bytes("rgb_libheif.heic");
    FakeWinApi api = fake();
    api.rejectStream = data -> !java.util.Arrays.equals(data, heic);
    assertArrayEquals(api.pixels, rgb(new WicDecoder(api, true).decode(heic, 0, true, 7)));
    assertEquals(2, api.streams.size());
    assertArrayEquals(heic, api.streams.get(1));
    assertEquals(2, api.coInitializeCalls);
    api.assertClean();

    FakeWinApi missing = fake();
    missing.rejectStream = data -> true;
    missing.rejectResult = Hresult.WINCODEC_ERR_COMPONENTINITIALIZEFAILURE;
    WicException e = assertThrows(WicException.class, () -> new WicDecoder(missing, true).decode(heic, 0, true, 7));
    assertEquals(Hresult.WINCODEC_ERR_COMPONENTINITIALIZEFAILURE, e.hresult());
    assertArrayEquals(heic, missing.streams.get(missing.streams.size() - 1), "the failure of the file itself");
    missing.assertClean();
  }

  @Test
  void resampleAlphaPlane() {
    byte[] plane = {0, (byte) 255, 0, (byte) 255};
    assertArrayEquals(plane, WicDecoder.resample(plane, 2, 2, 2, 2));
    byte[] up = WicDecoder.resample(plane, 2, 2, 4, 4);
    assertEquals(16, up.length);
    assertEquals(0, up[0] & 0xFF);
    assertEquals(255, up[3] & 0xFF);
  }

  @Test
  void typeIdentifier() {
    assertEquals("heic", WicDecoder.typeIdentifier(cn.yooss.heic.Fixtures.bytes("rgb_sips.heic"), Guids.GUID_ContainerFormatHeif));
    assertEquals("msf1", WicDecoder.typeIdentifier(cn.yooss.heic.Fixtures.bytes("seq.heics"), Guids.GUID_ContainerFormatHeif));
    assertEquals("heif", WicDecoder.typeIdentifier(new byte[4], Guids.GUID_ContainerFormatHeif));
    assertEquals("png", WicDecoder.typeIdentifier(DATA, Guids.GUID_ContainerFormatPng));
  }
}
