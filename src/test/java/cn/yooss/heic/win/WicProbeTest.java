package cn.yooss.heic.win;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.HeifInput;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The availability probe of the Windows backend, on every OS: the embedded test image, and the status that each
 * combination of WIC and Media Foundation results leads to (with {@link FakeWinApi}).
 */
class WicProbeTest {
  /** A fake whose frame looks like the decoded sample: 64x128, red on top, blue at the bottom. */
  private static FakeWinApi sampleLike() {
    FakeWinApi api = new FakeWinApi();
    int[] pixels = new int[64 * 128];
    for (int y = 0; y < 128; y++) {
      for (int x = 0; x < 64; x++) pixels[y * 64 + x] = y < 64 ? 0xFFFF1900 : 0xFF000FFF; // colors as Windows decodes them
    }
    api.setImage(64, 128, pixels);
    return api;
  }

  private static HeifBackendStatus probe(FakeWinApi api) {
    WicProbe probe = WicProbe.run(new WicDecoder(api));
    api.assertClean();
    return probe.toStatus();
  }

  @Test
  void sampleIsACompleteHeifFile() throws IOException {
    byte[] sample = WicProbe.sample();
    assertEquals(428, sample.length);
    HeifInput.check(sample);
  }

  /** The macOS decoder checks the embedded sample and what {@link WicProbe#checkSample} expects of it. */
  @Test
  void sampleDecodesAsExpected() throws IOException {
    HeifBackend backend = HeifBackends.current();
    assumeTrue(backend.status().isAvailable(), "no system decoder");
    HeifImageInfo info = backend.readInfo(WicProbe.sample());
    assertEquals("64x128", info.width() + "x" + info.height());
    BufferedImage image = backend.decode(WicProbe.sample(), 0);
    assertNull(WicProbe.checkSample(image), "decoded by " + backend.id());
  }

  @Test
  void checkSample() {
    BufferedImage upright = new BufferedImage(64, 128, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 128; y++) for (int x = 0; x < 64; x++) upright.setRGB(x, y, y < 64 ? 0xFF0000 : 0x0000FF);
    assertNull(WicProbe.checkSample(upright));
    BufferedImage stored = new BufferedImage(128, 64, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++) stored.setRGB(x, y, x < 64 ? 0xFF0000 : 0x0000FF);
    assertNull(WicProbe.checkSample(stored), "a decoder that neither applies nor reports irot");
    BufferedImage black = new BufferedImage(64, 128, BufferedImage.TYPE_INT_RGB);
    assertTrue(WicProbe.checkSample(black).contains("unexpected colors"), WicProbe.checkSample(black));
    assertTrue(WicProbe.checkSample(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)).contains("10x10"));
  }

  @Test
  void available() {
    FakeWinApi api = sampleLike();
    WicProbe probe = WicProbe.run(new WicDecoder(api));
    api.assertClean();
    HeifBackendStatus status = probe.toStatus();
    assertTrue(status.isAvailable(), status.toString());
    assertTrue(status.detail().contains("HEVC decoders: HEVCVideoExtension"), status.detail());
    assertTrue(status.detail().contains("decoded (64x128)"), status.detail());
    assertNull(status.installUrl());
  }

  /** What Windows Server 2025 and Windows 11 without the HEIF Image Extension report. */
  @Test
  void heifExtensionMissing() {
    FakeWinApi api = sampleLike();
    api.failures.put("CreateDecoder", Hresult.WINCODEC_ERR_COMPONENTINITIALIZEFAILURE);
    api.failures.put("CreateDecoderFromStream", Hresult.WINCODEC_ERR_COMPONENTINITIALIZEFAILURE);
    api.hevcDecoders = List.of();
    HeifBackendStatus status = probe(api);
    assertEquals(HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING, status.reason(), status.toString());
    assertEquals("ms-windows-store://pdp/?ProductId=9PMMSR1CGPWG", status.installUrl());
    assertEquals("winget install --id 9PMMSR1CGPWG --source msstore --accept-package-agreements", status.installCommand());
    HeifRemedy remedy = HeifRemedies.forStatus(status);
    assertNotNull(remedy);
    assertEquals(HeifRemedy.Action.openUrl("remedy.action.open.store", status.installUrl()), remedy.actions().get(0));
    assertEquals(status.installCommand(), remedy.command());
    assertTrue(status.isUserInstallable());
    assertTrue(status.detail().contains("0x88982F8B (WINCODEC_ERR_COMPONENTINITIALIZEFAILURE)"), status.detail());
    assertTrue(status.detail().contains("HEVC decoders: none"), status.detail());
  }

  @Test
  void heifExtensionMissingAccordingToEitherCall() {
    for (int hr : new int[]{Hresult.WINCODEC_ERR_COMPONENTNOTFOUND, Hresult.WINCODEC_ERR_UNKNOWNIMAGEFORMAT,
      Hresult.REGDB_E_CLASSNOTREG, Hresult.WINCODEC_ERR_COMPONENTINITIALIZEFAILURE}) {
      FakeWinApi fromStream = sampleLike();
      fromStream.failures.put("CreateDecoderFromStream", hr);
      assertEquals(HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING, probe(fromStream).reason(), Hresult.describe(hr));
      FakeWinApi fromCreate = sampleLike();
      fromCreate.failures.put("CreateDecoder", hr);
      fromCreate.failures.put("CopyPixels", Hresult.E_FAIL);
      assertEquals(HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING, probe(fromCreate).reason(), Hresult.describe(hr));
    }
  }

  /** What Windows 11 with the HEIF Image Extension but without the HEVC Video Extension reports. */
  @Test
  void hevcExtensionMissing() {
    FakeWinApi api = sampleLike();
    api.failures.put("CreateBitmapFromSource", Hresult.MF_E_TOPO_CODEC_NOT_FOUND);
    api.hevcDecoders = List.of();
    HeifBackendStatus status = probe(api);
    assertEquals(HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING, status.reason(), status.toString());
    assertEquals("ms-windows-store://pdp/?ProductId=9NMZLZ57R3T7", status.installUrl());
    assertNull(status.installCommand(), "a paid product: no command that could install it");
    assertTrue(status.detail().contains("MF_E_TOPO_CODEC_NOT_FOUND"), status.detail());
  }

  @Test
  void hevcMissingByEitherSign() {
    FakeWinApi codecError = sampleLike();
    codecError.failures.put("CopyPixels", Hresult.MF_E_TOPO_CODEC_NOT_FOUND);
    assertEquals(HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING, probe(codecError).reason());
    FakeWinApi noDecoders = sampleLike();
    noDecoders.failures.put("CopyPixels", Hresult.E_FAIL);
    noDecoders.hevcDecoders = List.of();
    assertEquals(HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING, probe(noDecoders).reason());
  }

  @Test
  void otherFailuresAreErrors() {
    FakeWinApi api = sampleLike();
    api.failures.put("CopyPixels", Hresult.E_ACCESSDENIED);
    HeifBackendStatus status = probe(api);
    assertEquals(HeifBackendStatus.Reason.ERROR, status.reason(), status.toString());
    assertNull(status.installUrl());
    assertTrue(status.detail().contains("E_ACCESSDENIED"), status.detail());

    FakeWinApi wrongPixels = sampleLike();
    wrongPixels.pixels = new int[64 * 128];
    HeifBackendStatus black = probe(wrongPixels);
    assertEquals(HeifBackendStatus.Reason.ERROR, black.reason());
    assertTrue(black.detail().contains("unexpected colors"), black.detail());
  }

  /** Windows "N" editions without the Media Feature Pack have no mfplat.dll. */
  @Test
  void withoutMediaFoundation() {
    FakeWinApi works = sampleLike();
    works.hevcDecoders = null;
    HeifBackendStatus available = probe(works);
    assertTrue(available.isAvailable());
    assertTrue(available.detail().contains("Media Foundation is not available"), available.detail());

    FakeWinApi broken = sampleLike();
    broken.hevcDecoders = null;
    broken.failures.put("CopyPixels", Hresult.E_FAIL);
    assertEquals(HeifBackendStatus.Reason.ERROR, probe(broken).reason());
  }

  @Test
  void comFailureOfTheProbeIsAnError() {
    FakeWinApi api = sampleLike();
    api.coInitializeResult = Hresult.E_UNEXPECTED;
    HeifBackendStatus status = probe(api);
    assertEquals(HeifBackendStatus.Reason.ERROR, status.reason());
    assertTrue(status.detail().contains("CreateDecoder(HEIF): not called (CoInitializeEx failed: 0x8000FFFF (E_UNEXPECTED))"),
               status.detail());
  }

  /** The backend on the fake: status, readInfo and decode go through the same decoder. */
  @ParameterizedTest
  @ValueSource(strings = {"amd64", "aarch64"})
  void backendOnTheFake(String arch) throws IOException {
    FakeWinApi api = sampleLike();
    WicHeifBackend backend = new WicHeifBackend(() -> new WicDecoder(api), "Windows 11", arch);
    assertTrue(backend.status().isAvailable(), backend.status().toString());
    BufferedImage image = backend.decode(Fixtures.bytes("rgb_sips.heic"), 0);
    assertEquals("64x128", image.getWidth() + "x" + image.getHeight());
    HeifImageInfo info = backend.readInfo(Fixtures.bytes("rgb_sips.heic"));
    assertEquals("64x128", info.width() + "x" + info.height());
    api.assertClean();
  }

  /** Elsewhere the backend reports UNSUPPORTED_OS without creating its decoder (no native library is loaded). */
  @ParameterizedTest
  @CsvSource({"Mac OS X, aarch64", "Linux, amd64", "Windows 11, x86", "Windows 10, arm"})
  void unsupportedPlatforms(String os, String arch) {
    WicHeifBackend backend = new WicHeifBackend(() -> {
      throw new AssertionError("the decoder must not be created on " + os + " " + arch);
    }, os, arch);
    HeifBackendStatus status = backend.status();
    assertEquals(HeifBackendStatus.Reason.UNSUPPORTED_OS, status.reason(), status.toString());
    assertThrows(IOException.class, () -> backend.decode(Fixtures.bytes("rgb_sips.heic"), 0));
  }
}
