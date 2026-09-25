package cn.yooss.heic.win;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An in-memory {@link WinApi}: COM objects with reference counts, one decodable frame with configurable pixels, alpha
 * plane, orientation and color contexts, "native" memory, and failure injection per method. Lets
 * {@link WicDecoder} and {@link WicProbe} run on every OS, and checks that every object is released, every buffer freed
 * and COM uninitialized exactly when it was initialized, on success and on every failure path.
 */
final class FakeWinApi implements WinApi {
  enum Kind { FACTORY, STREAM, DECODER, FRAME, TRANSFORM, SCALER, CONVERTER, BITMAP, COLOR_CONTEXT, QUERY_READER, INFO, INFO2, ACTIVATE }

  static final class Obj {
    final Kind kind;
    int references = 1;
    int width, height;
    int[] pixels; // 0xAARRGGBB
    String pixelFormat;
    boolean initialized;
    int contextType;
    byte[] profile;
    int exifColorSpace;

    Obj(Kind kind) {
      this.kind = kind;
    }
  }

  // ---------------------------------------------------------------- configuration
  String containerFormat = Guids.GUID_ContainerFormatHeif;
  int width = 4, height = 3;
  /** Straight 0xAARRGGBB; the frame's pixel format decides whether alpha is kept. */
  int[] pixels = new int[width * height];
  String framePixelFormat = Guids.GUID_WICPixelFormat32bppBGR;
  @Nullable byte[] alphaPlane;
  /** {@code System.Photo.Orientation}, or 0 for a frame without metadata. */
  int orientation = 1;
  @Nullable byte[] iccProfile;
  int exifColorSpace; // 0: no EXIF color space context
  int frameCount = 1;
  int coInitializeResult = Hresult.S_OK;
  List<String> hevcDecoders = new ArrayList<>(List.of("HEVCVideoExtension"));
  final Map<String, Integer> failures = new HashMap<>();

  // ---------------------------------------------------------------- bookkeeping
  final Map<Long, Obj> objects = new HashMap<>();
  final Map<Long, byte[]> memory = new HashMap<>();
  final List<String> calls = new ArrayList<>();
  int coInitializeCalls, coUninitializeCalls;
  private long next = 0x1000;

  void setImage(int width, int height, int[] pixels) {
    this.width = width;
    this.height = height;
    this.pixels = pixels;
  }

  private Integer failure(String method) {
    calls.add(method);
    return failures.get(method);
  }

  private long create(Obj obj) {
    long handle = next;
    next += 0x10;
    objects.put(handle, obj);
    return handle;
  }

  private Obj get(long handle, Kind... kinds) {
    Obj obj = objects.get(handle);
    if (obj == null) throw new AssertionError("Use of an unknown or released object 0x" + Long.toHexString(handle));
    for (Kind kind : kinds) if (obj.kind == kind) return obj;
    throw new AssertionError("Object 0x" + Long.toHexString(handle) + " is a " + obj.kind + ", expected one of " + List.of(kinds));
  }

  /** Every object released, every buffer freed, COM uninitialized exactly when it was initialized. */
  void assertClean() {
    assertEquals(Map.of(), objects, "leaked COM objects");
    assertEquals(0, memory.size(), "leaked native buffers");
    int expectedUninitialize = coInitializeResult == Hresult.S_OK || coInitializeResult == Hresult.S_FALSE ? coInitializeCalls : 0;
    assertEquals(expectedUninitialize, coUninitializeCalls, "CoUninitialize calls for " + coInitializeCalls + " CoInitializeEx calls");
  }

  @Override
  public @NotNull String name() {
    return "fake";
  }

  // ---------------------------------------------------------------- COM
  @Override
  public int coInitializeEx(int coInit) {
    calls.add("CoInitializeEx");
    assertEquals(0, coInit, "COINIT_MULTITHREADED");
    coInitializeCalls++;
    return coInitializeResult;
  }

  @Override
  public void coUninitialize() {
    calls.add("CoUninitialize");
    coUninitializeCalls++;
  }

  @Override
  public int coCreateInstance(@NotNull String clsid, int context, @NotNull String iid, long[] result) {
    Integer f = failure("CoCreateInstance");
    if (f != null) return f;
    assertEquals(Guids.CLSID_WICImagingFactory, clsid);
    assertEquals(Guids.IID_IWICImagingFactory, iid);
    result[0] = create(new Obj(Kind.FACTORY));
    return Hresult.S_OK;
  }

  @Override
  public void coTaskMemFree(long address) {
  }

  @Override
  public int queryInterface(long object, @NotNull String iid, long[] result) {
    Integer f = failure("QueryInterface");
    if (f != null) return f;
    Obj obj = objects.get(object);
    if (obj == null) throw new AssertionError("QueryInterface on unknown object");
    if (obj.kind == Kind.FRAME && iid.equals(Guids.IID_IWICBitmapSourceTransform)) {
      result[0] = create(new Obj(Kind.TRANSFORM));
      return Hresult.S_OK;
    }
    if (obj.kind == Kind.INFO && iid.equals(Guids.IID_IWICPixelFormatInfo2)) {
      Obj info2 = new Obj(Kind.INFO2);
      info2.pixelFormat = obj.pixelFormat;
      result[0] = create(info2);
      return Hresult.S_OK;
    }
    return Hresult.E_NOINTERFACE;
  }

  @Override
  public int release(long object) {
    calls.add("Release");
    Obj obj = objects.get(object);
    if (obj == null) throw new AssertionError("Release of an unknown or released object 0x" + Long.toHexString(object));
    if (--obj.references == 0) objects.remove(object);
    return obj.references;
  }

  @Override
  public long shCreateMemStream(byte @NotNull [] data) {
    Integer f = failure("SHCreateMemStream");
    if (f != null) return 0;
    return create(new Obj(Kind.STREAM));
  }

  // ---------------------------------------------------------------- factory
  @Override
  public int createDecoderFromStream(long factory, long stream, int metadataOptions, long[] decoder) {
    get(factory, Kind.FACTORY);
    get(stream, Kind.STREAM);
    Integer f = failure("CreateDecoderFromStream");
    if (f != null) return f;
    decoder[0] = create(new Obj(Kind.DECODER));
    return Hresult.S_OK;
  }

  @Override
  public int createDecoder(long factory, @NotNull String containerFormat, long[] decoder) {
    get(factory, Kind.FACTORY);
    Integer f = failure("CreateDecoder");
    if (f != null) return f;
    decoder[0] = create(new Obj(Kind.DECODER));
    return Hresult.S_OK;
  }

  @Override
  public int createFormatConverter(long factory, long[] converter) {
    get(factory, Kind.FACTORY);
    Integer f = failure("CreateFormatConverter");
    if (f != null) return f;
    converter[0] = create(new Obj(Kind.CONVERTER));
    return Hresult.S_OK;
  }

  @Override
  public int createBitmapScaler(long factory, long[] scaler) {
    get(factory, Kind.FACTORY);
    Integer f = failure("CreateBitmapScaler");
    if (f != null) return f;
    scaler[0] = create(new Obj(Kind.SCALER));
    return Hresult.S_OK;
  }

  @Override
  public int createColorContext(long factory, long[] context) {
    get(factory, Kind.FACTORY);
    Integer f = failure("CreateColorContext");
    if (f != null) return f;
    context[0] = create(new Obj(Kind.COLOR_CONTEXT));
    return Hresult.S_OK;
  }

  @Override
  public int createBitmapFromSource(long factory, long source, int cacheOption, long[] bitmap) {
    get(factory, Kind.FACTORY);
    Obj input = get(source, Kind.CONVERTER, Kind.SCALER, Kind.FRAME);
    Integer f = failure("CreateBitmapFromSource");
    if (f != null) return f;
    assertEquals(WicDecoder.WICBitmapCacheOnLoad, cacheOption);
    assertTrue(input.kind != Kind.CONVERTER || input.initialized, "converter initialized");
    Obj obj = new Obj(Kind.BITMAP);
    obj.width = input.width;
    obj.height = input.height;
    obj.pixels = input.pixels.clone();
    obj.pixelFormat = input.pixelFormat;
    bitmap[0] = create(obj);
    return Hresult.S_OK;
  }

  @Override
  public int createComponentInfo(long factory, @NotNull String clsid, long[] info) {
    get(factory, Kind.FACTORY);
    Integer f = failure("CreateComponentInfo");
    if (f != null) return f;
    Obj obj = new Obj(Kind.INFO);
    obj.pixelFormat = clsid;
    info[0] = create(obj);
    return Hresult.S_OK;
  }

  // ---------------------------------------------------------------- decoder
  @Override
  public int getContainerFormat(long decoder, String[] format) {
    get(decoder, Kind.DECODER);
    Integer f = failure("GetContainerFormat");
    if (f != null) return f;
    format[0] = containerFormat;
    return Hresult.S_OK;
  }

  @Override
  public int getFrameCount(long decoder, int[] count) {
    get(decoder, Kind.DECODER);
    Integer f = failure("GetFrameCount");
    if (f != null) return f;
    count[0] = frameCount;
    return Hresult.S_OK;
  }

  @Override
  public int getFrame(long decoder, int index, long[] frame) {
    get(decoder, Kind.DECODER);
    Integer f = failure("GetFrame");
    if (f != null) return f;
    assertEquals(0, index);
    Obj obj = new Obj(Kind.FRAME);
    obj.width = width;
    obj.height = height;
    obj.pixelFormat = framePixelFormat;
    obj.pixels = pixels.clone();
    frame[0] = create(obj);
    return Hresult.S_OK;
  }

  // ---------------------------------------------------------------- bitmap sources
  @Override
  public int getSize(long source, int[] size) {
    Obj obj = get(source, Kind.FRAME, Kind.SCALER, Kind.CONVERTER, Kind.BITMAP);
    Integer f = failure("GetSize");
    if (f != null) return f;
    size[0] = obj.width;
    size[1] = obj.height;
    return Hresult.S_OK;
  }

  @Override
  public int getPixelFormat(long source, String[] format) {
    Obj obj = get(source, Kind.FRAME, Kind.SCALER, Kind.CONVERTER, Kind.BITMAP);
    Integer f = failure("GetPixelFormat");
    if (f != null) return f;
    format[0] = obj.pixelFormat;
    return Hresult.S_OK;
  }

  @Override
  public int copyPixels(long source, int x, int y, int width, int height, int stride, int bufferSize, long buffer) {
    Obj obj = get(source, Kind.FRAME, Kind.SCALER, Kind.CONVERTER, Kind.BITMAP);
    Integer f = failure("CopyPixels");
    if (f != null) return f;
    byte[] target = memory.get(buffer);
    if (target == null) throw new AssertionError("CopyPixels into unknown memory");
    if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > obj.width || y + height > obj.height) {
      return Hresult.E_INVALIDARG;
    }
    if (stride < 4 * width || (long) stride * height > bufferSize || bufferSize > target.length) {
      return Hresult.WINCODEC_ERR_INSUFFICIENTBUFFER;
    }
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        int p = obj.pixels[(y + row) * obj.width + x + column];
        int at = row * stride + 4 * column;
        target[at] = (byte) p;
        target[at + 1] = (byte) (p >> 8);
        target[at + 2] = (byte) (p >> 16);
        target[at + 3] = (byte) (p >>> 24);
      }
    }
    return Hresult.S_OK;
  }

  // ---------------------------------------------------------------- frame
  @Override
  public int getMetadataQueryReader(long frame, long[] reader) {
    get(frame, Kind.FRAME);
    Integer f = failure("GetMetadataQueryReader");
    if (f != null) return f;
    if (orientation == 0) return Hresult.WINCODEC_ERR_UNSUPPORTEDOPERATION;
    reader[0] = create(new Obj(Kind.QUERY_READER));
    return Hresult.S_OK;
  }

  @Override
  public int getColorContexts(long frame, long[] contexts, int[] actual) {
    get(frame, Kind.FRAME);
    Integer f = failure("GetColorContexts");
    if (f != null) return f;
    List<Obj> available = new ArrayList<>();
    if (exifColorSpace != 0) {
      Obj exif = new Obj(Kind.COLOR_CONTEXT);
      exif.contextType = WicDecoder.WICColorContextExifColorSpace;
      exif.exifColorSpace = exifColorSpace;
      available.add(exif);
    }
    if (iccProfile != null) {
      Obj icc = new Obj(Kind.COLOR_CONTEXT);
      icc.contextType = WicDecoder.WICColorContextProfile;
      icc.profile = iccProfile;
      available.add(icc);
    }
    if (contexts.length == 0) {
      actual[0] = available.size();
      return Hresult.S_OK;
    }
    int n = Math.min(contexts.length, available.size());
    for (int i = 0; i < n; i++) {
      Obj target = get(contexts[i], Kind.COLOR_CONTEXT);
      target.contextType = available.get(i).contextType;
      target.profile = available.get(i).profile;
      target.exifColorSpace = available.get(i).exifColorSpace;
    }
    actual[0] = n;
    return Hresult.S_OK;
  }

  @Override
  public int getMetadataInteger(long reader, @NotNull String name, long[] value) {
    get(reader, Kind.QUERY_READER);
    Integer f = failure("GetMetadataByName");
    if (f != null) return f;
    assertEquals(WicDecoder.ORIENTATION_POLICY, name);
    value[0] = orientation;
    return Hresult.S_OK;
  }

  // ---------------------------------------------------------------- converter, scaler
  @Override
  public int initializeFormatConverter(long converter, long source, @NotNull String pixelFormat) {
    Obj obj = get(converter, Kind.CONVERTER);
    Obj input = get(source, Kind.FRAME, Kind.SCALER);
    Integer f = failure("IWICFormatConverter::Initialize");
    if (f != null) return f;
    obj.width = input.width;
    obj.height = input.height;
    obj.pixelFormat = pixelFormat;
    obj.pixels = input.pixels.clone();
    boolean keepAlpha = pixelFormat.equals(Guids.GUID_WICPixelFormat32bppBGRA)
                        && input.pixelFormat.equals(Guids.GUID_WICPixelFormat32bppBGRA);
    for (int i = 0; i < obj.pixels.length; i++) {
      // 32bppBGR leaves the fourth byte undefined: 0x5A here, so the decoder must not rely on it.
      if (!keepAlpha) obj.pixels[i] = pixelFormat.equals(Guids.GUID_WICPixelFormat32bppBGR) ? 0x5A000000 | (obj.pixels[i] & 0xFFFFFF)
                                                                                           : 0xFF000000 | obj.pixels[i];
    }
    obj.initialized = true;
    return Hresult.S_OK;
  }

  @Override
  public int initializeBitmapScaler(long scaler, long source, int width, int height, int interpolationMode) {
    Obj obj = get(scaler, Kind.SCALER);
    Obj input = get(source, Kind.FRAME);
    Integer f = failure("IWICBitmapScaler::Initialize");
    if (f != null) return f;
    assertEquals(WicDecoder.WICBitmapInterpolationModeFant, interpolationMode);
    obj.width = width;
    obj.height = height;
    obj.pixelFormat = input.pixelFormat;
    obj.pixels = scale(input.pixels, input.width, input.height, width, height);
    obj.initialized = true;
    return Hresult.S_OK;
  }

  static int[] scale(int[] source, int width, int height, int targetWidth, int targetHeight) {
    int[] result = new int[targetWidth * targetHeight];
    for (int y = 0; y < targetHeight; y++) {
      for (int x = 0; x < targetWidth; x++) {
        result[y * targetWidth + x] = source[(y * height / targetHeight) * width + x * width / targetWidth];
      }
    }
    return result;
  }

  // ---------------------------------------------------------------- source transform
  @Override
  public int getClosestPixelFormat(long transform, String[] format) {
    get(transform, Kind.TRANSFORM);
    Integer f = failure("GetClosestPixelFormat");
    if (f != null) return f;
    if (!(Guids.GUID_WICPixelFormat8bppAlpha.equals(format[0]) && alphaPlane != null)) format[0] = framePixelFormat;
    return Hresult.S_OK;
  }

  @Override
  public int getClosestSize(long transform, int[] size) {
    get(transform, Kind.TRANSFORM);
    Integer f = failure("GetClosestSize");
    return f != null ? f : Hresult.S_OK;
  }

  @Override
  public int copyTransformedPixels(long transform, int width, int height, @NotNull String pixelFormat, int transformOptions,
                                   int stride, int bufferSize, long buffer) {
    get(transform, Kind.TRANSFORM);
    Integer f = failure("IWICBitmapSourceTransform::CopyPixels");
    if (f != null) return f;
    if (!Guids.GUID_WICPixelFormat8bppAlpha.equals(pixelFormat) || alphaPlane == null) return Hresult.WINCODEC_ERR_UNSUPPORTEDPIXELFORMAT;
    byte[] target = memory.get(buffer);
    if (target == null || (long) stride * height > bufferSize || bufferSize > target.length) return Hresult.E_INVALIDARG;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        target[y * stride + x] = alphaPlane[(y * this.height / height) * this.width + x * this.width / width];
      }
    }
    return Hresult.S_OK;
  }

  // ---------------------------------------------------------------- color contexts
  @Override
  public int getColorContextType(long context, int[] type) {
    Obj obj = get(context, Kind.COLOR_CONTEXT);
    Integer f = failure("IWICColorContext::GetType");
    if (f != null) return f;
    type[0] = obj.contextType;
    return Hresult.S_OK;
  }

  @Override
  public int getProfileBytes(long context, byte @Nullable [] buffer, int[] actual) {
    Obj obj = get(context, Kind.COLOR_CONTEXT);
    Integer f = failure("GetProfileBytes");
    if (f != null) return f;
    if (obj.profile == null) return Hresult.WINCODEC_ERR_WRONGSTATE;
    actual[0] = obj.profile.length;
    if (buffer != null) {
      if (buffer.length < obj.profile.length) return Hresult.WINCODEC_ERR_INSUFFICIENTBUFFER;
      System.arraycopy(obj.profile, 0, buffer, 0, obj.profile.length);
    }
    return Hresult.S_OK;
  }

  @Override
  public int getExifColorSpace(long context, int[] value) {
    Obj obj = get(context, Kind.COLOR_CONTEXT);
    value[0] = obj.exifColorSpace;
    return Hresult.S_OK;
  }

  @Override
  public int supportsTransparency(long pixelFormatInfo2, int[] supported) {
    Obj obj = get(pixelFormatInfo2, Kind.INFO2);
    Integer f = failure("SupportsTransparency");
    if (f != null) return f;
    supported[0] = Guids.GUID_WICPixelFormat32bppBGRA.equals(obj.pixelFormat) ? 1 : 0;
    return Hresult.S_OK;
  }

  // ---------------------------------------------------------------- Media Foundation
  @Override
  public int enumerateVideoDecoders(@NotNull String subtype, int flags, @NotNull List<String> friendlyNames) {
    Integer f = failure("MFTEnumEx");
    if (f != null) return f;
    if (hevcDecoders == null) throw new UnsatisfiedLinkError("Unable to load library 'mfplat'");
    friendlyNames.addAll(hevcDecoders);
    return Hresult.S_OK;
  }

  // ---------------------------------------------------------------- memory
  @Override
  public long malloc(long size) {
    Integer f = failure("malloc");
    if (f != null) return 0;
    long handle = next;
    next += 0x10;
    memory.put(handle, new byte[(int) size]);
    return handle;
  }

  @Override
  public void free(long address) {
    if (address != 0 && memory.remove(address) == null) throw new AssertionError("free of unknown memory");
  }

  @Override
  public void readInts(long address, int[] target, int count) {
    byte[] source = memory.get(address);
    for (int i = 0; i < count; i++) {
      target[i] = (source[4 * i] & 0xFF) | (source[4 * i + 1] & 0xFF) << 8 | (source[4 * i + 2] & 0xFF) << 16
                  | (source[4 * i + 3] & 0xFF) << 24;
    }
  }

  @Override
  public void readBytes(long address, byte[] target, int count) {
    System.arraycopy(memory.get(address), 0, target, 0, count);
  }
}
