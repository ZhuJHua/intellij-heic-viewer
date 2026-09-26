package cn.yooss.heic.win.jna;

import cn.yooss.heic.backend.jna.JnaLibraries;
import cn.yooss.heic.win.Guids;
import cn.yooss.heic.win.Hresult;
import cn.yooss.heic.win.WinApi;
import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * {@link WinApi} on top of the JNA that every IntelliJ-based IDE bundles, following the rules of {@link JnaLibraries}
 * (no {@code Library} interface, no {@code Structure}, no {@code Memory}, no callbacks; only {@link NativeLibrary},
 * {@link Function#invokeInt(Object[])} etc. with arguments of JDK types, and {@link Native#malloc}/{@link Native#free}),
 * so that the plugin class loader stays collectable; {@code jna-platform}'s COM helpers are {@code Structure}s and
 * {@code Library} proxies that would register plugin-loaded state in JNA's caches.
 * <p>
 * <b>COM methods</b> are called through the object's vtable: {@code *(void***) object} is the vtable, slot {@code i}
 * holds the function pointer of the {@code i}-th method, whose first argument is the object itself. The slots below
 * are the declaration order of the methods in the interface and its base interfaces, from the Windows SDK's
 * {@code wincodec.idl} (as published in microsoft/win32metadata, {@code generation/WinSDK/RecompiledIdlHeaders/um}),
 * {@code objidlbase.idl} and {@code mfobjects.idl}, cross-checked against the generated C {@code Vtbl} structs of the
 * mingw-w64 headers ({@code mingw-w64-headers/include/wincodec.h}, {@code mfobjects.h}):
 * <pre>
 * IUnknown                   0 QueryInterface  1 AddRef  2 Release
 * IWICImagingFactory         4 CreateDecoderFromStream  6 CreateComponentInfo  7 CreateDecoder  10 CreateFormatConverter
 *                            11 CreateBitmapScaler  15 CreateColorContext  18 CreateBitmapFromSource
 * IWICBitmapDecoder          5 GetContainerFormat  12 GetFrameCount  13 GetFrame
 * IWICBitmapSource           3 GetSize  4 GetPixelFormat  7 CopyPixels  (inherited by the frame, scaler, converter, bitmap)
 * IWICBitmapFrameDecode      8 GetMetadataQueryReader  9 GetColorContexts
 * IWICFormatConverter        8 Initialize
 * IWICBitmapScaler           8 Initialize
 * IWICBitmapSourceTransform  3 CopyPixels  4 GetClosestSize  5 GetClosestPixelFormat
 * IWICColorContext           6 GetType  7 GetProfileBytes  8 GetExifColorSpace
 * IWICMetadataQueryReader    5 GetMetadataByName
 * IWICPixelFormatInfo2       16 SupportsTransparency  (IWICComponentInfo 3-10, IWICPixelFormatInfo 11-15)
 * IMFActivate                13 GetAllocatedString  (IMFAttributes 3-32)
 * </pre>
 * <b>Calling convention.</b> Only 64-bit Windows is supported (x64 and arm64; one calling convention each, so
 * {@code STDMETHODCALLTYPE} needs no special handling). Interface pointers and other pointers are passed as
 * {@code Long}, {@code UINT}/{@code BOOL}/enums as {@code Integer}, {@code REFGUID}/{@code REFIID} as a {@code byte[16]}
 * (JNA passes a pointer to a copy), out-parameters as one-element {@code long[]}/{@code int[]} arrays (JNA copies them
 * back), wide strings as NUL-terminated {@code char[]} ({@link JnaLibraries#utf16z}). {@code MFTEnumEx} takes its
 * category {@code GUID} <em>by value</em>: on x64 a 16-byte struct is passed as a pointer to a caller-owned copy (so a
 * {@code byte[16]}), on arm64 (AAPCS64, which Windows follows for non-variadic calls) a composite of at most 16 bytes
 * that is not a floating-point aggregate travels in two consecutive general-purpose registers (so two {@code Long}s
 * holding the little-endian halves); see {@link GuidByValue}.
 */
public final class JnaWinApi implements WinApi {
  private static final int MF_VERSION = 0x0002_0070; // MF_SDK_VERSION 2 << 16 | MF_API_VERSION 0x70 (mfapi.h, Windows 7+)
  private static final int MFSTARTUP_LITE = 1;
  private static final int PROPVARIANT_BYTES = 32; // sizeof(PROPVARIANT) is 24 on 64-bit Windows; a little headroom
  private static final int VT_I2 = 2, VT_I4 = 3, VT_UI1 = 17, VT_UI2 = 18, VT_UI4 = 19, VT_INT = 22, VT_UINT = 23;

  // Opened through JnaLibraries (no options; JNA's Cleaner thread guarded). Media Foundation is optional (mfplat).
  private final NativeLibrary[] libraries = JnaLibraries.openAll("ole32", "shlwapi");
  private final NativeLibrary ole32 = libraries[0];
  private final NativeLibrary shlwapi = libraries[1];

  private final Function coInitializeEx = ole32.getFunction("CoInitializeEx");
  private final Function coUninitialize = ole32.getFunction("CoUninitialize");
  private final Function coCreateInstance = ole32.getFunction("CoCreateInstance");
  private final Function coTaskMemFree = ole32.getFunction("CoTaskMemFree");
  private final Function propVariantClear = ole32.getFunction("PropVariantClear");
  private final Function shCreateMemStream = shlwapi.getFunction("SHCreateMemStream");

  private final GuidByValue guidByValue = GuidByValue.forCurrentPlatform();

  public JnaWinApi() {
  }

  @Override
  public @NotNull String name() {
    return "JNA " + JnaLibraries.version() + " (" + System.getProperty("os.arch", "?") + ")";
  }

  // ---------------------------------------------------------------- vtable calls
  /** The function in vtable slot {@code slot} of the COM object at {@code object}. */
  private static Function method(long object, int slot) {
    if (object == 0) throw new IllegalArgumentException("NULL COM interface pointer");
    Pointer vtable = new Pointer(object).getPointer(0);
    if (vtable == null) throw new IllegalStateException("COM object without vtable");
    Pointer function = vtable.getPointer((long) slot * Native.POINTER_SIZE);
    if (function == null) throw new IllegalStateException("Empty vtable slot " + slot);
    return Function.getFunction(function);
  }

  /** Calls the {@code HRESULT}-returning method in vtable slot {@code slot} with {@code object} as {@code this}. */
  private static int call(long object, int slot, Object... arguments) {
    Object[] all = new Object[arguments.length + 1];
    all[0] = object;
    System.arraycopy(arguments, 0, all, 1, arguments.length);
    return method(object, slot).invokeInt(all);
  }

  private static byte[] guid(String guid) {
    return Guids.bytes(guid);
  }

  // ---------------------------------------------------------------- COM (ole32)
  @Override
  public int coInitializeEx(int coInit) {
    return coInitializeEx.invokeInt(new Object[]{null, coInit});
  }

  @Override
  public void coUninitialize() {
    coUninitialize.invokeVoid(new Object[0]);
  }

  @Override
  public int coCreateInstance(@NotNull String clsid, int context, @NotNull String iid, long[] result) {
    return coCreateInstance.invokeInt(new Object[]{guid(clsid), null, context, guid(iid), result});
  }

  @Override
  public void coTaskMemFree(long address) {
    if (address != 0) coTaskMemFree.invokeVoid(new Object[]{address});
  }

  /** {@code IUnknown::QueryInterface(REFIID, void**)}: slot 0. */
  @Override
  public int queryInterface(long object, @NotNull String iid, long[] result) {
    return call(object, 0, guid(iid), result);
  }

  /** {@code IUnknown::Release()}: slot 2 (returns a {@code ULONG}). */
  @Override
  public int release(long object) {
    return call(object, 2);
  }

  // ---------------------------------------------------------------- shlwapi
  @Override
  public long shCreateMemStream(byte @NotNull [] data) {
    return shCreateMemStream.invokeLong(new Object[]{data, data.length});
  }

  // ---------------------------------------------------------------- IWICImagingFactory
  /** {@code CreateDecoderFromStream(IStream*, const GUID* pguidVendor, WICDecodeOptions, IWICBitmapDecoder**)}: slot 4. */
  @Override
  public int createDecoderFromStream(long factory, long stream, int metadataOptions, long[] decoder) {
    return call(factory, 4, stream, null, metadataOptions, decoder);
  }

  /** {@code CreateDecoder(REFGUID guidContainerFormat, const GUID* pguidVendor, IWICBitmapDecoder**)}: slot 7. */
  @Override
  public int createDecoder(long factory, @NotNull String containerFormat, long[] decoder) {
    return call(factory, 7, guid(containerFormat), null, decoder);
  }

  /** {@code CreateFormatConverter(IWICFormatConverter**)}: slot 10. */
  @Override
  public int createFormatConverter(long factory, long[] converter) {
    return call(factory, 10, converter);
  }

  /** {@code CreateBitmapScaler(IWICBitmapScaler**)}: slot 11. */
  @Override
  public int createBitmapScaler(long factory, long[] scaler) {
    return call(factory, 11, scaler);
  }

  /** {@code CreateColorContext(IWICColorContext**)}: slot 15. */
  @Override
  public int createColorContext(long factory, long[] context) {
    return call(factory, 15, context);
  }

  /** {@code CreateBitmapFromSource(IWICBitmapSource*, WICBitmapCreateCacheOption, IWICBitmap**)}: slot 18. */
  @Override
  public int createBitmapFromSource(long factory, long source, int cacheOption, long[] bitmap) {
    return call(factory, 18, source, cacheOption, bitmap);
  }

  /** {@code CreateComponentInfo(REFCLSID clsidComponent, IWICComponentInfo**)}: slot 6. */
  @Override
  public int createComponentInfo(long factory, @NotNull String clsid, long[] info) {
    return call(factory, 6, guid(clsid), info);
  }

  // ---------------------------------------------------------------- IWICBitmapDecoder
  /** {@code GetContainerFormat(GUID*)}: slot 5. */
  @Override
  public int getContainerFormat(long decoder, String[] format) {
    byte[] bytes = new byte[16];
    int hr = call(decoder, 5, (Object) bytes);
    if (Hresult.succeeded(hr)) format[0] = Guids.string(bytes);
    return hr;
  }

  /** {@code GetFrameCount(UINT*)}: slot 12. */
  @Override
  public int getFrameCount(long decoder, int[] count) {
    return call(decoder, 12, (Object) count);
  }

  /** {@code GetFrame(UINT index, IWICBitmapFrameDecode**)}: slot 13. */
  @Override
  public int getFrame(long decoder, int index, long[] frame) {
    return call(decoder, 13, index, frame);
  }

  // ---------------------------------------------------------------- IWICBitmapSource
  /** {@code GetSize(UINT* puiWidth, UINT* puiHeight)}: slot 3. */
  @Override
  public int getSize(long source, int[] size) {
    int[] width = new int[1], height = new int[1];
    int hr = call(source, 3, width, height);
    if (Hresult.succeeded(hr)) {
      size[0] = width[0];
      size[1] = height[0];
    }
    return hr;
  }

  /** {@code GetPixelFormat(WICPixelFormatGUID*)}: slot 4. */
  @Override
  public int getPixelFormat(long source, String[] format) {
    byte[] bytes = new byte[16];
    int hr = call(source, 4, (Object) bytes);
    if (Hresult.succeeded(hr)) format[0] = Guids.string(bytes);
    return hr;
  }

  /** {@code CopyPixels(const WICRect* prc, UINT cbStride, UINT cbBufferSize, BYTE* pbBuffer)}: slot 7. */
  @Override
  public int copyPixels(long source, int x, int y, int width, int height, int stride, int bufferSize, long buffer) {
    int[] rect = {x, y, width, height}; // WICRect: four INTs
    return call(source, 7, rect, stride, bufferSize, buffer);
  }

  // ---------------------------------------------------------------- IWICBitmapFrameDecode
  /** {@code GetMetadataQueryReader(IWICMetadataQueryReader**)}: slot 8. */
  @Override
  public int getMetadataQueryReader(long frame, long[] reader) {
    return call(frame, 8, (Object) reader);
  }

  /** {@code GetColorContexts(UINT cCount, IWICColorContext** ppIColorContexts, UINT* pcActualCount)}: slot 9. */
  @Override
  public int getColorContexts(long frame, long[] contexts, int[] actual) {
    return call(frame, 9, contexts.length, contexts.length == 0 ? null : contexts, actual);
  }

  // ---------------------------------------------------------------- IWICMetadataQueryReader
  /** {@code GetMetadataByName(LPCWSTR wzName, PROPVARIANT* pvarValue)}: slot 5. */
  @Override
  public int getMetadataInteger(long reader, @NotNull String name, long[] value) {
    byte[] variant = new byte[PROPVARIANT_BYTES];
    int hr = call(reader, 5, JnaLibraries.utf16z(name), variant);
    if (Hresult.failed(hr)) return hr;
    try {
      int type = u16(variant, 0);
      switch (type) {
        case VT_UI1: value[0] = variant[8] & 0xFF; return Hresult.S_OK;
        case VT_UI2: value[0] = u16(variant, 8); return Hresult.S_OK;
        case VT_I2: value[0] = (short) u16(variant, 8); return Hresult.S_OK;
        case VT_UI4: case VT_UINT: value[0] = s32(variant, 8) & 0xFFFFFFFFL; return Hresult.S_OK;
        case VT_I4: case VT_INT: value[0] = s32(variant, 8); return Hresult.S_OK;
        default: return Hresult.S_FALSE;
      }
    }
    finally {
      if (u16(variant, 0) != 0) propVariantClear.invokeInt(new Object[]{variant}); // frees strings, blobs, interfaces
    }
  }

  private static int u16(byte[] b, int offset) {
    return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8;
  }

  private static int s32(byte[] b, int offset) {
    return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8 | (b[offset + 2] & 0xFF) << 16 | (b[offset + 3] & 0xFF) << 24;
  }

  private static long s64(byte[] b, int offset) {
    return (s32(b, offset) & 0xFFFFFFFFL) | (long) s32(b, offset + 4) << 32;
  }

  // ---------------------------------------------------------------- IWICFormatConverter, IWICBitmapScaler
  /**
   * {@code IWICFormatConverter::Initialize(IWICBitmapSource*, REFWICPixelFormatGUID, WICBitmapDitherType,
   * IWICPalette*, double alphaThresholdPercent, WICBitmapPaletteType)}: slot 8.
   */
  @Override
  public int initializeFormatConverter(long converter, long source, @NotNull String pixelFormat) {
    return call(converter, 8, source, guid(pixelFormat), 0 /* WICBitmapDitherTypeNone */, null, 0.0d,
                0 /* WICBitmapPaletteTypeCustom */);
  }

  /** {@code IWICBitmapScaler::Initialize(IWICBitmapSource*, UINT, UINT, WICBitmapInterpolationMode)}: slot 8. */
  @Override
  public int initializeBitmapScaler(long scaler, long source, int width, int height, int interpolationMode) {
    return call(scaler, 8, source, width, height, interpolationMode);
  }

  // ---------------------------------------------------------------- IWICBitmapSourceTransform
  /** {@code GetClosestPixelFormat(WICPixelFormatGUID* pguidDstFormat)} (in/out): slot 5. */
  @Override
  public int getClosestPixelFormat(long transform, String[] format) {
    byte[] bytes = guid(format[0]);
    int hr = call(transform, 5, (Object) bytes);
    if (Hresult.succeeded(hr)) format[0] = Guids.string(bytes);
    return hr;
  }

  /** {@code GetClosestSize(UINT* puiWidth, UINT* puiHeight)} (in/out): slot 4. */
  @Override
  public int getClosestSize(long transform, int[] size) {
    int[] width = {size[0]}, height = {size[1]};
    int hr = call(transform, 4, width, height);
    if (Hresult.succeeded(hr)) {
      size[0] = width[0];
      size[1] = height[0];
    }
    return hr;
  }

  /**
   * {@code CopyPixels(const WICRect* prc, UINT uiWidth, UINT uiHeight, WICPixelFormatGUID* pguidDstFormat,
   * WICBitmapTransformOptions dstTransform, UINT nStride, UINT cbBufferSize, BYTE* pbBuffer)}: slot 3.
   */
  @Override
  public int copyTransformedPixels(long transform, int width, int height, @NotNull String pixelFormat,
                                   int transformOptions, int stride, int bufferSize, long buffer) {
    return call(transform, 3, null, width, height, guid(pixelFormat), transformOptions, stride, bufferSize, buffer);
  }

  // ---------------------------------------------------------------- IWICColorContext
  /** {@code GetType(WICColorContextType*)}: slot 6. */
  @Override
  public int getColorContextType(long context, int[] type) {
    return call(context, 6, (Object) type);
  }

  /** {@code GetProfileBytes(UINT cbBuffer, BYTE* pbBuffer, UINT* pcbActual)}: slot 7. */
  @Override
  public int getProfileBytes(long context, byte @Nullable [] buffer, int[] actual) {
    return call(context, 7, buffer == null ? 0 : buffer.length, buffer, actual);
  }

  /** {@code GetExifColorSpace(UINT*)}: slot 8. */
  @Override
  public int getExifColorSpace(long context, int[] value) {
    return call(context, 8, (Object) value);
  }

  // ---------------------------------------------------------------- IWICPixelFormatInfo2
  /** {@code SupportsTransparency(BOOL*)}: slot 16. */
  @Override
  public int supportsTransparency(long pixelFormatInfo2, int[] supported) {
    return call(pixelFormatInfo2, 16, (Object) supported);
  }

  // ---------------------------------------------------------------- Media Foundation
  @Override
  public int enumerateVideoDecoders(@NotNull String subtype, int flags, @NotNull List<String> friendlyNames) {
    MediaFoundation mf = MediaFoundation.get();
    int hr = mf.startup.invokeInt(new Object[]{MF_VERSION, MFSTARTUP_LITE});
    if (Hresult.failed(hr)) return hr;
    try {
      byte[] inputType = new byte[32]; // MFT_REGISTER_TYPE_INFO {GUID guidMajorType; GUID guidSubtype;}
      System.arraycopy(guid(Guids.MFMediaType_Video), 0, inputType, 0, 16);
      System.arraycopy(guid(subtype), 0, inputType, 16, 16);
      long[] activates = new long[1];
      int[] count = new int[1];
      Object[] category = guidByValue.arguments(guid(Guids.MFT_CATEGORY_VIDEO_DECODER));
      Object[] arguments = new Object[category.length + 5];
      System.arraycopy(category, 0, arguments, 0, category.length);
      arguments[category.length] = flags;
      arguments[category.length + 1] = inputType;
      arguments[category.length + 2] = null; // pOutputType
      arguments[category.length + 3] = activates;
      arguments[category.length + 4] = count;
      hr = mf.enumEx.invokeInt(arguments);
      if (Hresult.failed(hr) || activates[0] == 0) return hr;
      try {
        Pointer array = new Pointer(activates[0]);
        for (int i = 0; i < count[0]; i++) {
          long activate = array.getLong((long) i * Native.POINTER_SIZE);
          if (activate == 0) continue;
          try {
            friendlyNames.add(friendlyName(activate));
          }
          finally {
            release(activate);
          }
        }
      }
      finally {
        coTaskMemFree(activates[0]);
      }
      return hr;
    }
    finally {
      mf.shutdown.invokeInt(new Object[0]);
    }
  }

  /** {@code IMFAttributes::GetAllocatedString(REFGUID, LPWSTR*, UINT32*)} (slot 13) of MFT_FRIENDLY_NAME_Attribute. */
  private String friendlyName(long activate) {
    long[] text = new long[1];
    int[] length = new int[1];
    int hr = call(activate, 13, guid(Guids.MFT_FRIENDLY_NAME_Attribute), text, length);
    if (Hresult.failed(hr) || text[0] == 0) return "?";
    try {
      return new Pointer(text[0]).getWideString(0);
    }
    finally {
      coTaskMemFree(text[0]);
    }
  }

  /** Media Foundation, loaded on first use: it is missing on Windows "N" editions without the Media Feature Pack. */
  private static final class MediaFoundation {
    private static volatile MediaFoundation instance;

    final Function startup;
    final Function shutdown;
    final Function enumEx;

    private MediaFoundation(NativeLibrary mfplat) {
      startup = mfplat.getFunction("MFStartup");
      shutdown = mfplat.getFunction("MFShutdown");
      enumEx = mfplat.getFunction("MFTEnumEx");
    }

    static MediaFoundation get() {
      MediaFoundation result = instance;
      if (result == null) {
        result = new MediaFoundation(JnaLibraries.open("mfplat"));
        instance = result;
      }
      return result;
    }
  }

  // ---------------------------------------------------------------- native memory
  @Override
  public long malloc(long size) {
    return Native.malloc(size);
  }

  @Override
  public void free(long address) {
    if (address != 0) Native.free(address);
  }

  @Override
  public void readInts(long address, int[] target, int count) {
    new Pointer(address).read(0, target, 0, count);
  }

  @Override
  public void readBytes(long address, byte[] target, int count) {
    new Pointer(address).read(0, target, 0, count);
  }

  /**
   * How a {@code GUID} parameter passed by value (16 bytes, four 32-bit-aligned members, no floating point) is spread
   * over JNA arguments. Package-private for tests.
   * <ul>
   *   <li>x64: "Structs or unions of other sizes [than 1, 2, 4 or 8 bytes] are passed as a pointer to memory allocated
   *   by the caller" (Microsoft, x64 calling convention, Parameter passing): a {@code byte[16]}, which JNA passes as a
   *   pointer to a temporary copy.</li>
   *   <li>arm64: Windows follows AAPCS64 for non-variadic functions (Microsoft, ARM64 ABI conventions); rule C.10: a
   *   composite of at most 16 bytes that is not an HFA/HVA is "copied into consecutive general-purpose registers", as
   *   if loaded with LDR from memory: two {@code Long}s, the little-endian first and second halves.</li>
   * </ul>
   */
  enum GuidByValue {
    POINTER_TO_COPY, TWO_REGISTERS;

    static GuidByValue forCurrentPlatform() {
      String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      if (arch.equals("aarch64") || arch.equals("arm64")) return TWO_REGISTERS;
      if (arch.equals("amd64") || arch.equals("x86_64")) return POINTER_TO_COPY;
      throw new UnsatisfiedLinkError("Unsupported architecture for GUID passing: " + arch);
    }

    Object[] arguments(byte[] guid) {
      if (this == POINTER_TO_COPY) return new Object[]{guid};
      return new Object[]{s64(guid, 0), s64(guid, 8)};
    }
  }
}
