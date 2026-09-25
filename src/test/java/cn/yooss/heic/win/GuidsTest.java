package cn.yooss.heic.win;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GUID layout, HRESULT names and the ICC profile peek of the Windows backend (pure Java, every OS). */
class GuidsTest {
  /** {@code DEFINE_GUID(CLSID_WICImagingFactory, 0xcacaf262, 0x9370, 0x4615, 0xa1, 0x3b, 0x9f, 0x55, 0x39, 0xda, 0x4c, 0x0a)}. */
  @Test
  void memoryLayout() {
    byte[] expected = {(byte) 0x62, (byte) 0xf2, (byte) 0xca, (byte) 0xca, (byte) 0x70, (byte) 0x93, (byte) 0x15, (byte) 0x46,
      (byte) 0xa1, (byte) 0x3b, (byte) 0x9f, (byte) 0x55, (byte) 0x39, (byte) 0xda, (byte) 0x4c, (byte) 0x0a};
    assertArrayEquals(expected, Guids.bytes(Guids.CLSID_WICImagingFactory));
    assertEquals(Guids.CLSID_WICImagingFactory, Guids.string(expected));
  }

  /** Media types are FourCC-based: {@code MFVideoFormat_HEVC} starts with 'H' 'E' 'V' 'C' in memory. */
  @Test
  void fourCc() {
    byte[] hevc = Guids.bytes(Guids.MFVideoFormat_HEVC);
    assertEquals("HEVC", new String(hevc, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    assertEquals("H264", new String(Guids.bytes(Guids.MFVideoFormat_H264), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    assertEquals("vids", new String(Guids.bytes(Guids.MFMediaType_Video), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
  }

  @ParameterizedTest
  @ValueSource(strings = {Guids.IID_IWICImagingFactory, Guids.GUID_ContainerFormatHeif, Guids.GUID_WICPixelFormat32bppBGRA,
    Guids.GUID_WICPixelFormat8bppAlpha, Guids.MFT_CATEGORY_VIDEO_DECODER, Guids.IID_IWICBitmapSourceTransform,
    Guids.IID_IWICPixelFormatInfo2, Guids.MFT_FRIENDLY_NAME_Attribute})
  void roundTrip(String guid) {
    assertEquals(guid, Guids.string(Guids.bytes(guid)));
    assertEquals(16, Guids.bytes(guid).length);
  }

  @Test
  void invalid() {
    assertThrows(IllegalArgumentException.class, () -> Guids.bytes("cacaf262-9370-4615-a13b-9f5539da4c0"));
    assertThrows(IllegalArgumentException.class, () -> Guids.bytes("cacaf262x9370-4615-a13b-9f5539da4c0a"));
    assertThrows(IllegalArgumentException.class, () -> Guids.bytes("zacaf262-9370-4615-a13b-9f5539da4c0a"));
    assertThrows(IllegalArgumentException.class, () -> Guids.string(new byte[15]));
  }

  @Test
  void hresultNames() {
    assertEquals("0x88982F50 (WINCODEC_ERR_COMPONENTNOTFOUND)", Hresult.describe(Hresult.WINCODEC_ERR_COMPONENTNOTFOUND));
    assertEquals("0xC00D5212 (MF_E_TOPO_CODEC_NOT_FOUND)", Hresult.describe(Hresult.MF_E_TOPO_CODEC_NOT_FOUND));
    assertEquals("0x80010106 (RPC_E_CHANGED_MODE)", Hresult.describe(Hresult.RPC_E_CHANGED_MODE));
    assertEquals("0x12345678", Hresult.describe(0x12345678));
    assertTrue(Hresult.failed(Hresult.E_FAIL));
    assertTrue(Hresult.succeeded(Hresult.S_FALSE));
    WicException e = new WicException("IWICBitmap::CopyPixels", Hresult.E_INVALIDARG);
    assertEquals("IWICBitmap::CopyPixels failed: 0x80070057 (E_INVALIDARG)", e.getMessage());
  }

  @Test
  void iccProfiles() {
    byte[] srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
    assertTrue(IccProfiles.isSrgb(srgb), IccProfiles.description(srgb));
    byte[] linear = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
    assertFalse(IccProfiles.isSrgb(linear), IccProfiles.description(linear));
    byte[] gray = ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData();
    assertFalse(IccProfiles.isSrgb(gray), "not an RGB profile");
    assertFalse(IccProfiles.isSrgb(new byte[200]));
    assertFalse(IccProfiles.isSrgb(new byte[3]));
    assertNull(IccProfiles.description(null));
    assertEquals("linear sRGB", IccProfiles.description(linear).trim(), "named sRGB, but linear: must be converted");
    for (String p3 : new String[]{"/System/Library/ColorSync/Profiles/Display P3.icc"}) {
      java.nio.file.Path path = java.nio.file.Paths.get(p3);
      if (java.nio.file.Files.isRegularFile(path)) {
        try {
          assertFalse(IccProfiles.isSrgb(java.nio.file.Files.readAllBytes(path)), p3);
        }
        catch (java.io.IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      }
    }
    byte[] truncated = java.util.Arrays.copyOf(srgb, 140);
    assertFalse(IccProfiles.isSrgb(truncated));
  }
}
