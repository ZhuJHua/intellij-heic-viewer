package cn.yooss.heic.win;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The native primitives {@link WicDecoder} needs from COM ({@code ole32}), {@code shlwapi}, the Windows Imaging
 * Component (WIC) and Media Foundation ({@code mfplat}). The decode algorithm exists once, in {@link WicDecoder}; the
 * binding sits behind this interface: {@link cn.yooss.heic.win.jna.JnaWinApi} (the IDE's bundled JNA), or a fake in the
 * tests, which checks on every OS that each COM object is released and COM is uninitialized on all paths.
 * <p>
 * Conventions: COM interface pointers are passed as raw addresses ({@code 0} = {@code NULL}); methods return the
 * {@code HRESULT} of the call and write out-parameters into the given one-element arrays; GUIDs are canonical strings
 * (see {@link Guids}). The method names are those of the Windows SDK; each implementation documents the vtable slot it
 * calls. Implementations must be thread-safe and keep no per-call state.
 */
public interface WinApi {
  /** Short name for logs, e.g. {@code "JNA 5.17.0 (amd64)"}. */
  @NotNull String name();

  // ---------------------------------------------------------------- COM (ole32)
  /** {@code CoInitializeEx(NULL, coInit)}. */
  int coInitializeEx(int coInit);

  /** {@code CoUninitialize()}. */
  void coUninitialize();

  /** {@code CoCreateInstance(clsid, NULL, context, iid, &result[0])}. */
  int coCreateInstance(@NotNull String clsid, int context, @NotNull String iid, long[] result);

  /** {@code CoTaskMemFree}; a no-op for {@code 0}. */
  void coTaskMemFree(long address);

  /** {@code IUnknown::QueryInterface}. */
  int queryInterface(long object, @NotNull String iid, long[] result);

  /** {@code IUnknown::Release}; returns the new reference count. */
  int release(long object);

  // ---------------------------------------------------------------- shlwapi
  /** {@code SHCreateMemStream(data, data.length)}: a read/write {@code IStream} on a copy of {@code data}, or {@code 0}. */
  long shCreateMemStream(byte @NotNull [] data);

  // ---------------------------------------------------------------- IWICImagingFactory
  int createDecoderFromStream(long factory, long stream, int metadataOptions, long[] decoder);

  /** {@code CreateDecoder(containerFormat, NULL, &decoder)}. */
  int createDecoder(long factory, @NotNull String containerFormat, long[] decoder);

  int createFormatConverter(long factory, long[] converter);

  int createBitmapScaler(long factory, long[] scaler);

  int createColorContext(long factory, long[] context);

  int createBitmapFromSource(long factory, long source, int cacheOption, long[] bitmap);

  /** {@code CreateComponentInfo(clsid, &info)}; a pixel format GUID yields an {@code IWICPixelFormatInfo}. */
  int createComponentInfo(long factory, @NotNull String clsid, long[] info);

  // ---------------------------------------------------------------- IWICBitmapDecoder
  int getContainerFormat(long decoder, String[] format);

  int getFrameCount(long decoder, int[] count);

  int getFrame(long decoder, int index, long[] frame);

  // ---------------------------------------------------------------- IWICBitmapSource (frames, scalers, converters, bitmaps)
  /** {@code GetSize(&size[0], &size[1])}: width and height. */
  int getSize(long source, int[] size);

  int getPixelFormat(long source, String[] format);

  /** {@code CopyPixels(&WICRect{x, y, width, height}, stride, bufferSize, buffer)}; {@code buffer} is native memory. */
  int copyPixels(long source, int x, int y, int width, int height, int stride, int bufferSize, long buffer);

  // ---------------------------------------------------------------- IWICBitmapFrameDecode
  int getMetadataQueryReader(long frame, long[] reader);

  /**
   * {@code GetColorContexts(contexts.length, contexts, &actual[0])}: with an empty array, the number of color contexts;
   * otherwise initializes the given (factory-created) contexts.
   */
  int getColorContexts(long frame, long[] contexts, int[] actual);

  // ---------------------------------------------------------------- IWICMetadataQueryReader
  /**
   * {@code GetMetadataByName(name, &propvariant)} for an integer item ({@code VT_UI1}, {@code VT_UI2}, {@code VT_UI4},
   * {@code VT_I2}, {@code VT_I4}): writes the value and returns {@code S_OK}; returns {@code S_FALSE} for an item of
   * another type. The {@code PROPVARIANT} is always cleared.
   */
  int getMetadataInteger(long reader, @NotNull String name, long[] value);

  // ---------------------------------------------------------------- IWICFormatConverter, IWICBitmapScaler
  /** {@code Initialize(source, pixelFormat, WICBitmapDitherTypeNone, NULL, 0.0, WICBitmapPaletteTypeCustom)}. */
  int initializeFormatConverter(long converter, long source, @NotNull String pixelFormat);

  int initializeBitmapScaler(long scaler, long source, int width, int height, int interpolationMode);

  // ---------------------------------------------------------------- IWICBitmapSourceTransform
  /** {@code GetClosestPixelFormat(&format[0])} (in/out). */
  int getClosestPixelFormat(long transform, String[] format);

  /** {@code GetClosestSize(&size[0], &size[1])} (in/out). */
  int getClosestSize(long transform, int[] size);

  /** {@code CopyPixels(NULL, width, height, &pixelFormat, transformOptions, stride, bufferSize, buffer)}. */
  int copyTransformedPixels(long transform, int width, int height, @NotNull String pixelFormat, int transformOptions,
                            int stride, int bufferSize, long buffer);

  // ---------------------------------------------------------------- IWICColorContext
  int getColorContextType(long context, int[] type);

  /** {@code GetProfileBytes(buffer.length, buffer, &actual[0])}; a {@code null} buffer asks for the size. */
  int getProfileBytes(long context, byte @Nullable [] buffer, int[] actual);

  // ---------------------------------------------------------------- IWICPixelFormatInfo2
  /** {@code IWICPixelFormatInfo2::SupportsTransparency(&supported[0])} (a {@code BOOL}). */
  int supportsTransparency(long pixelFormatInfo2, int[] supported);

  // ---------------------------------------------------------------- Media Foundation (mfplat)
  /**
   * {@code MFStartup(MF_VERSION, MFSTARTUP_LITE)}, {@code MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER, flags,
   * {MFMediaType_Video, subtype}, NULL, ...)}, the friendly name of each transform, {@code MFShutdown()}. Every
   * {@code IMFActivate} and the array are released.
   *
   * @return the {@code HRESULT} of {@code MFStartup} if it failed, else that of {@code MFTEnumEx}
   * @throws UnsatisfiedLinkError if Media Foundation is not installed (e.g. Windows "N" editions without the Media
   *                              Feature Pack)
   */
  int enumerateVideoDecoders(@NotNull String subtype, int flags, @NotNull List<String> friendlyNames);

  // ---------------------------------------------------------------- native memory
  /** {@code malloc(size)}, at least 8-byte aligned; {@code 0} if the allocation failed. */
  long malloc(long size);

  /** {@code free(address)}; a no-op for {@code 0}. */
  void free(long address);

  /** Copies {@code count} native-endian ints starting at {@code address} into {@code target[0..count)}. */
  void readInts(long address, int[] target, int count);

  /** Copies {@code count} bytes starting at {@code address} into {@code target[0..count)}. */
  void readBytes(long address, byte[] target, int count);
}
