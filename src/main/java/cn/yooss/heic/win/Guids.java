package cn.yooss.heic.win;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * GUIDs of the COM classes, interfaces and formats the Windows backend uses, as canonical lower-case strings, and their
 * 16-byte in-memory layout. Values from the Windows SDK headers ({@code wincodec.idl}, {@code wincodecsdk.idl},
 * {@code mfapi.h}, {@code mftransform.idl}).
 */
public final class Guids {
  private Guids() {
  }

  // ---------------------------------------------------------------- COM classes and interfaces (wincodec.idl)
  public static final String CLSID_WICImagingFactory = "cacaf262-9370-4615-a13b-9f5539da4c0a";
  public static final String IID_IWICImagingFactory = "ec5ec8a9-c395-4314-9c77-54d7a935ff70";
  public static final String IID_IWICBitmapSourceTransform = "3b16811b-6a43-4ec9-b713-3d5a0c13b940";
  public static final String IID_IWICPixelFormatInfo2 = "a9db33a2-af5f-43c7-b679-74f5984b5aa4";

  // ---------------------------------------------------------------- container formats (wincodec.idl)
  /** Windows 10 1809+: the container format of the "HEIF Image Extension" decoder. */
  public static final String GUID_ContainerFormatHeif = "e1e62521-6787-405b-a339-500715b5763f";
  public static final String GUID_ContainerFormatPng = "1b7cfaf4-713f-473c-bbcd-6137425faeaf";
  public static final String GUID_ContainerFormatJpeg = "19e4a5aa-5662-4fc5-a0c0-1758028e1057";
  public static final String GUID_ContainerFormatTiff = "163bcc30-e2e9-4f0b-961d-a3e9fdb788a3";
  public static final String GUID_ContainerFormatBmp = "0af1d87e-fcfe-4188-bdeb-a7906471cbe3";
  public static final String GUID_ContainerFormatGif = "1f8a5601-7d4d-4cbd-9c82-1bc8d4eeb9a5";

  // ---------------------------------------------------------------- pixel formats (wincodec.idl)
  public static final String GUID_WICPixelFormat32bppBGR = "6fddc324-4e03-4bfe-b185-3d77768dc90e";
  public static final String GUID_WICPixelFormat32bppBGRA = "6fddc324-4e03-4bfe-b185-3d77768dc90f";
  /** An 8-bit alpha plane; the HEIF decoder hands out the alpha of an image through IWICBitmapSourceTransform. */
  public static final String GUID_WICPixelFormat8bppAlpha = "e6cd0116-eeba-4161-aa85-27dd9fb3a895";

  // ---------------------------------------------------------------- Media Foundation (mfapi.h, mftransform.idl)
  public static final String MFT_CATEGORY_VIDEO_DECODER = "d6c02d4b-6833-45b4-971a-05a4b04bab91";
  public static final String MFMediaType_Video = "73646976-0000-0010-8000-00aa00389b71";
  /** {@code DEFINE_MEDIATYPE_GUID(MFVideoFormat_HEVC, FCC('HEVC'))}. */
  public static final String MFVideoFormat_HEVC = "43564548-0000-0010-8000-00aa00389b71";
  /** {@code DEFINE_MEDIATYPE_GUID(MFVideoFormat_H264, FCC('H264'))} (tests: a decoder most systems have). */
  public static final String MFVideoFormat_H264 = "34363248-0000-0010-8000-00aa00389b71";
  public static final String MFT_FRIENDLY_NAME_Attribute = "314ffbae-5b41-4c95-9c19-4e7d586face3";

  /**
   * The 16 bytes of a {@code GUID} in memory: {@code Data1} (32 bit), {@code Data2} and {@code Data3} (16 bit each)
   * little-endian, then the 8 bytes of {@code Data4} in order.
   *
   * @throws IllegalArgumentException if {@code guid} is not of the form {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}
   */
  public static byte @NotNull [] bytes(@NotNull String guid) {
    String hex = guid.replace("-", "");
    if (guid.length() != 36 || hex.length() != 32 || guid.charAt(8) != '-' || guid.charAt(13) != '-'
        || guid.charAt(18) != '-' || guid.charAt(23) != '-') {
      throw new IllegalArgumentException("Not a GUID: " + guid);
    }
    byte[] big = new byte[16];
    for (int i = 0; i < 16; i++) {
      int hi = Character.digit(hex.charAt(2 * i), 16), lo = Character.digit(hex.charAt(2 * i + 1), 16);
      if (hi < 0 || lo < 0) throw new IllegalArgumentException("Not a GUID: " + guid);
      big[i] = (byte) (hi << 4 | lo);
    }
    byte[] result = big.clone();
    swap(result, 0, 3);
    swap(result, 1, 2);
    swap(result, 4, 5);
    swap(result, 6, 7);
    return result;
  }

  /** The canonical lower-case form of the 16 bytes of a {@code GUID} (see {@link #bytes}). */
  public static @NotNull String string(byte @NotNull [] bytes) {
    if (bytes.length < 16) throw new IllegalArgumentException("A GUID has 16 bytes, got " + bytes.length);
    byte[] big = new byte[16];
    System.arraycopy(bytes, 0, big, 0, 16);
    swap(big, 0, 3);
    swap(big, 1, 2);
    swap(big, 4, 5);
    swap(big, 6, 7);
    StringBuilder text = new StringBuilder(36);
    for (int i = 0; i < 16; i++) {
      if (i == 4 || i == 6 || i == 8 || i == 10) text.append('-');
      text.append(String.format(Locale.ROOT, "%02x", big[i] & 0xFF));
    }
    return text.toString();
  }

  private static void swap(byte[] b, int i, int j) {
    byte t = b[i];
    b[i] = b[j];
    b[j] = t;
  }
}
