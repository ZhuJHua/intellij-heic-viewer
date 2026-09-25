package cn.yooss.heic.win;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code HRESULT} values the Windows backend handles or reports, with their names for idea.log (values from
 * {@code winerror.h} and {@code mferror.h} of the Windows SDK).
 */
public final class Hresult {
  private Hresult() {
  }

  public static final int S_OK = 0;
  /** E.g. {@code CoInitializeEx}: COM was already initialized on this thread in the same apartment (must be balanced). */
  public static final int S_FALSE = 1;
  public static final int E_NOTIMPL = 0x80004001;
  public static final int E_NOINTERFACE = 0x80004002;
  public static final int E_POINTER = 0x80004003;
  public static final int E_FAIL = 0x80004005;
  public static final int E_UNEXPECTED = 0x8000FFFF;
  public static final int E_ACCESSDENIED = 0x80070005;
  public static final int E_OUTOFMEMORY = 0x8007000E;
  public static final int E_INVALIDARG = 0x80070057;
  public static final int REGDB_E_CLASSNOTREG = 0x80040154;
  public static final int CO_E_NOTINITIALIZED = 0x800401F0;
  /** {@code CoInitializeEx}: the thread already belongs to another apartment (e.g. a single-threaded one). */
  public static final int RPC_E_CHANGED_MODE = 0x80010106;

  public static final int WINCODEC_ERR_WRONGSTATE = 0x88982F04;
  public static final int WINCODEC_ERR_VALUEOUTOFRANGE = 0x88982F05;
  /** No WIC decoder recognizes the data. */
  public static final int WINCODEC_ERR_UNKNOWNIMAGEFORMAT = 0x88982F07;
  public static final int WINCODEC_ERR_PROPERTYNOTFOUND = 0x88982F40;
  public static final int WINCODEC_ERR_COMPONENTNOTFOUND = 0x88982F50;
  public static final int WINCODEC_ERR_IMAGESIZEOUTOFRANGE = 0x88982F51;
  public static final int WINCODEC_ERR_BADIMAGE = 0x88982F60;
  public static final int WINCODEC_ERR_BADHEADER = 0x88982F61;
  public static final int WINCODEC_ERR_FRAMEMISSING = 0x88982F62;
  public static final int WINCODEC_ERR_BADSTREAMDATA = 0x88982F70;
  public static final int WINCODEC_ERR_STREAMREAD = 0x88982F72;
  public static final int WINCODEC_ERR_UNSUPPORTEDPIXELFORMAT = 0x88982F80;
  public static final int WINCODEC_ERR_UNSUPPORTEDOPERATION = 0x88982F81;
  /**
   * The decoder registered for the data could not be created. {@code CreateDecoderFromStream} returns it for HEIF data
   * when the "HEIF Image Extensions" are not installed (observed on Windows 11 25H2 and Windows Server 2025: WIC keeps a
   * registration for the HEIF decoder whose implementation comes with the Store package).
   */
  public static final int WINCODEC_ERR_COMPONENTINITIALIZEFAILURE = 0x88982F8B;
  public static final int WINCODEC_ERR_INSUFFICIENTBUFFER = 0x88982F8C;
  /**
   * Media Foundation found no transform for the content: what the HEIF decoder's {@code CopyPixels} returns when the
   * "HEVC Video Extensions" (the HEVC decoder) are not installed.
   */
  public static final int MF_E_TOPO_CODEC_NOT_FOUND = 0xC00D5212;

  private static final Map<Integer, String> NAMES = new HashMap<>();

  static {
    name(S_OK, "S_OK");
    name(S_FALSE, "S_FALSE");
    name(E_NOTIMPL, "E_NOTIMPL");
    name(E_NOINTERFACE, "E_NOINTERFACE");
    name(E_POINTER, "E_POINTER");
    name(E_FAIL, "E_FAIL");
    name(E_UNEXPECTED, "E_UNEXPECTED");
    name(E_ACCESSDENIED, "E_ACCESSDENIED");
    name(E_OUTOFMEMORY, "E_OUTOFMEMORY");
    name(E_INVALIDARG, "E_INVALIDARG");
    name(REGDB_E_CLASSNOTREG, "REGDB_E_CLASSNOTREG");
    name(CO_E_NOTINITIALIZED, "CO_E_NOTINITIALIZED");
    name(RPC_E_CHANGED_MODE, "RPC_E_CHANGED_MODE");
    name(WINCODEC_ERR_WRONGSTATE, "WINCODEC_ERR_WRONGSTATE");
    name(WINCODEC_ERR_VALUEOUTOFRANGE, "WINCODEC_ERR_VALUEOUTOFRANGE");
    name(WINCODEC_ERR_UNKNOWNIMAGEFORMAT, "WINCODEC_ERR_UNKNOWNIMAGEFORMAT");
    name(WINCODEC_ERR_PROPERTYNOTFOUND, "WINCODEC_ERR_PROPERTYNOTFOUND");
    name(WINCODEC_ERR_COMPONENTNOTFOUND, "WINCODEC_ERR_COMPONENTNOTFOUND");
    name(WINCODEC_ERR_IMAGESIZEOUTOFRANGE, "WINCODEC_ERR_IMAGESIZEOUTOFRANGE");
    name(WINCODEC_ERR_BADIMAGE, "WINCODEC_ERR_BADIMAGE");
    name(WINCODEC_ERR_BADHEADER, "WINCODEC_ERR_BADHEADER");
    name(WINCODEC_ERR_FRAMEMISSING, "WINCODEC_ERR_FRAMEMISSING");
    name(WINCODEC_ERR_BADSTREAMDATA, "WINCODEC_ERR_BADSTREAMDATA");
    name(WINCODEC_ERR_STREAMREAD, "WINCODEC_ERR_STREAMREAD");
    name(WINCODEC_ERR_UNSUPPORTEDPIXELFORMAT, "WINCODEC_ERR_UNSUPPORTEDPIXELFORMAT");
    name(WINCODEC_ERR_UNSUPPORTEDOPERATION, "WINCODEC_ERR_UNSUPPORTEDOPERATION");
    name(WINCODEC_ERR_COMPONENTINITIALIZEFAILURE, "WINCODEC_ERR_COMPONENTINITIALIZEFAILURE");
    name(WINCODEC_ERR_INSUFFICIENTBUFFER, "WINCODEC_ERR_INSUFFICIENTBUFFER");
    name(MF_E_TOPO_CODEC_NOT_FOUND, "MF_E_TOPO_CODEC_NOT_FOUND");
  }

  private static void name(int hr, String name) {
    NAMES.put(hr, name);
  }

  public static boolean succeeded(int hr) {
    return hr >= 0;
  }

  public static boolean failed(int hr) {
    return hr < 0;
  }

  /** E.g. {@code "0x88982F50 (WINCODEC_ERR_COMPONENTNOTFOUND)"}. */
  public static @NotNull String describe(int hr) {
    String hex = String.format(Locale.ROOT, "0x%08X", hr);
    String name = NAMES.get(hr);
    return name != null ? hex + " (" + name + ")" : hex;
  }
}
