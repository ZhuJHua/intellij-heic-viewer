package cn.yooss.heic.win;

import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.PixelPipeline;
import cn.yooss.heic.backend.PixelPipeline.ByteLayout;
import cn.yooss.heic.backend.PlaneConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Decodes images with the Windows Imaging Component (WIC), written against {@link WinApi}.
 * <p>
 * Algorithm (one self-contained {@link Session} per call, so the class is thread-safe):
 * <ol>
 *   <li>COM on the calling thread: {@code CoInitializeEx(NULL, COINIT_MULTITHREADED)}. {@code S_OK} and
 *   {@code S_FALSE} (already initialized in the multithreaded apartment) are balanced by {@code CoUninitialize} at the
 *   end; {@code RPC_E_CHANGED_MODE} (the thread is in a single-threaded apartment, e.g. an AWT thread) means COM is
 *   usable as it is and must not be uninitialized. WIC's objects are free-threaded, so either apartment works.</li>
 *   <li>{@code CoCreateInstance(CLSID_WICImagingFactory)}, {@code SHCreateMemStream} on a copy of the data,
 *   {@code CreateDecoderFromStream} (WIC picks the decoder by content, which is why only data that passed
 *   {@code HeifInput.check} gets here), and the decoder's container format must be {@code GUID_ContainerFormatHeif}.</li>
 *   <li>Frame 0 (the primary image), its size, alpha and the orientation still to be applied. The Microsoft HEIF
 *   decoder applies the HEIF {@code irot}/{@code imir} transformations itself and reports
 *   {@code System.Photo.Orientation} = 1 (ignoring the EXIF orientation, as the HEIF standard requires); the reported
 *   orientation is applied anyway, which is what Windows' own viewers do. Its frames are always
 *   {@code 32bppBGR}: the alpha of an image is only available as a separate {@code 8bppAlpha} plane through
 *   {@code IWICBitmapSourceTransform} (its {@code GetClosestPixelFormat} returns {@code 8bppAlpha} only for images with
 *   alpha).</li>
 *   <li>{@code IWICBitmapScaler} (Fant) when the image is larger than requested, {@code IWICFormatConverter} to
 *   {@code 32bppBGR} ({@code 32bppBGRA}, straight alpha, for other formats with alpha), {@code CreateBitmapFromSource}
 *   with {@code WICBitmapCacheOnLoad} (decodes once), then {@code CopyPixels} in strips into the
 *   {@link BufferedImage}; the alpha plane is merged in. An image with an alpha plane is not scaled by WIC (up to
 *   {@link #ALPHA_WEIGHTED_MAX_PIXELS}): WIC would scale its colors and its alpha plane separately, so the color of the
 *   transparent pixels (black in HEIC files) would darken every edge. Its full-size frame and alpha plane are
 *   combined strip by strip and downscaled with an alpha-weighted filter ({@link PlaneConverter}) instead.</li>
 *   <li>An embedded ICC profile ({@code IWICColorContext} of type profile, e.g. Display P3 of iPhone photos) is
 *   converted to sRGB with {@link PixelPipeline#convertToSrgb}; an EXIF color space context of sRGB needs nothing.
 *   The YCbCr to RGB conversion is the decoder's, with two workarounds for HEIF Image Extension 1.2.36, applied to a
 *   copy of the data: a primary image that is a single 8-bit HEVC image is converted with the BT.709 matrix whatever
 *   the file signals (BT.601 red becomes (255, 25, 0)), so such files are decoded as a 1x1 grid of that image
 *   ({@link SingleImageGrid}), which the decoder converts right; and the BT.709-like and unspecified transfer curves
 *   that the decoder converts to sRGB, unlike every other viewer, are presented as sRGB ({@link NclxTransfer}). If
 *   the decoder rejects the rewritten data, the file is decoded as it is.</li>
 * </ol>
 * Every COM object and native buffer is released in {@link Session#close()}, in reverse order, on every path. All
 * failures are {@link IOException}s ({@link WicException} with the {@code HRESULT} for failed calls).
 */
public final class WicDecoder {
  static final int COINIT_MULTITHREADED = 0x0;
  static final int CLSCTX_INPROC_SERVER = 0x1;
  static final int WICDecodeMetadataCacheOnDemand = 0;
  static final int WICBitmapCacheOnLoad = 2;
  static final int WICBitmapInterpolationModeFant = 3;
  static final int WICBitmapTransformRotate0 = 0;
  static final int WICColorContextProfile = 1;
  static final int WICColorContextExifColorSpace = 2;
  /**
   * Largest image with an alpha plane that is downscaled with the alpha-weighted filter rather than with WIC's scaler
   * (the default pixel budget, 64 MP): that needs the full-size frame and alpha plane in native memory (5 bytes per
   * pixel), so larger images, which the pixel budget downscales anyway, keep WIC's scaler and its darker edges.
   */
  static final long ALPHA_WEIGHTED_MAX_PIXELS = 64_000_000L;
  /** {@code System.Photo.Orientation} photo metadata policy: the EXIF-style orientation still to be applied. */
  static final String ORIENTATION_POLICY = "System.Photo.Orientation";
  /**
   * System property: {@code false} switches the color workarounds ({@link SingleImageGrid}, {@link NclxTransfer}) off,
   * so that HEIF files are decoded exactly as WIC decodes them.
   */
  public static final String COLOR_FIXES_PROPERTY = "heic.viewer.windows.colorFixes";

  private final WinApi api;
  private final boolean colorFixes;

  public WicDecoder(@NotNull WinApi api) {
    this(api, !"false".equalsIgnoreCase(System.getProperty(COLOR_FIXES_PROPERTY, "true").trim()));
  }

  /** @param colorFixes whether the color workarounds are applied ({@link #COLOR_FIXES_PROPERTY}) */
  WicDecoder(@NotNull WinApi api, boolean colorFixes) {
    this.api = api;
    this.colorFixes = colorFixes;
  }

  /** Whether the color workarounds are applied ({@link #COLOR_FIXES_PROPERTY}). */
  public boolean colorFixes() {
    return colorFixes;
  }

  /**
   * The data WIC gets for a HEIF file: a rewritten copy with the color workarounds, or {@code data} itself if none
   * applies (or they are switched off).
   */
  byte @NotNull [] forDecoder(byte @NotNull [] data) {
    if (!colorFixes) return data;
    byte[] grid = SingleImageGrid.wrap(data); // a copy: the transfer curves are then set in place (one copy at most)
    if (grid != null) {
      NclxTransfer.asSrgbInPlace(grid);
      return grid;
    }
    byte[] srgb = NclxTransfer.asSrgb(data);
    return srgb != null ? srgb : data;
  }

  public @NotNull WinApi api() {
    return api;
  }

  /** How the decoded image gets its alpha. */
  enum Alpha {
    /** Opaque. */
    NONE,
    /** The frame's pixel format has alpha (other WIC formats, e.g. PNG). */
    IN_PIXELS,
    /** A separate {@code 8bppAlpha} plane from {@code IWICBitmapSourceTransform} (the HEIF decoder). */
    PLANE
  }

  /** What {@link Session#open} found out about the data. */
  static final class Opened {
    final String containerFormat;
    final int frameCount;
    final long frame;
    final int width;
    final int height;
    final String pixelFormat;
    final Alpha alpha;
    /** {@code IWICBitmapSourceTransform} of the frame when {@link #alpha} is {@link Alpha#PLANE}, else {@code 0}. */
    final long transform;
    final int orientation;

    Opened(String containerFormat, int frameCount, long frame, int width, int height, String pixelFormat, Alpha alpha,
           long transform, int orientation) {
      this.containerFormat = containerFormat;
      this.frameCount = frameCount;
      this.frame = frame;
      this.width = width;
      this.height = height;
      this.pixelFormat = pixelFormat;
      this.alpha = alpha;
      this.transform = transform;
      this.orientation = orientation;
    }
  }

  /**
   * Size, orientation and alpha of the primary image (no pixels are decoded; the HEIF decoder parses the container
   * only, so this works without the HEVC codec).
   *
   * @param requireHeif {@code false} only in tests: accept any container WIC can decode (PNG, JPEG, ...)
   */
  public @NotNull HeifImageInfo readInfo(byte @NotNull [] data, boolean requireHeif) throws IOException {
    try (Session session = new Session(api)) {
      Opened opened = session.open(data, requireHeif);
      return new HeifImageInfo(typeIdentifier(data, opened.containerFormat), Math.max(1, opened.frameCount), 0,
                               opened.width, opened.height, opened.orientation, -1, opened.alpha != Alpha.NONE);
    }
  }

  /**
   * Decodes the primary image, orientation applied.
   *
   * @param maxPixelSize {@code 0} for full resolution, otherwise the maximum length of the longer side
   * @param requireHeif  {@code false} only in tests (see {@link #readInfo})
   * @param stripPixels  pixels copied per {@code CopyPixels} call ({@link PixelPipeline#STRIP_PIXELS}; tests vary it)
   */
  public @NotNull BufferedImage decode(byte @NotNull [] data, int maxPixelSize, boolean requireHeif, int stripPixels)
    throws IOException {
    if (maxPixelSize < 0) throw new IllegalArgumentException("maxPixelSize must be >= 0: " + maxPixelSize);
    byte[] fixed = requireHeif ? forDecoder(data) : data;
    if (fixed != data) {
      try {
        return decodeOnce(fixed, maxPixelSize, requireHeif, stripPixels);
      }
      catch (IOException e) {
        // the decoder does not take the rewritten data: decode the file as it is (and report that failure, if any)
      }
    }
    return decodeOnce(data, maxPixelSize, requireHeif, stripPixels);
  }

  private @NotNull BufferedImage decodeOnce(byte[] data, int maxPixelSize, boolean requireHeif, int stripPixels)
    throws IOException {
    try (Session session = new Session(api)) {
      Opened opened = session.open(data, requireHeif);
      BufferedImage image = session.render(opened, maxPixelSize, stripPixels);
      byte[] icc = session.iccProfile(opened.frame);
      if (icc != null && !IccProfiles.isSrgb(icc)) PixelPipeline.convertToSrgb(image, icc);
      return PixelPipeline.applyOrientation(image, opened.orientation);
    }
  }

  /**
   * The size of the result for {@code maxPixelSize}: the image itself when it fits (or for {@code 0}), otherwise the
   * longer side becomes {@code maxPixelSize}, aspect ratio kept (like {@link PixelPipeline#downscale}).
   */
  static int[] targetSize(int width, int height, int maxPixelSize) {
    int longest = Math.max(width, height);
    if (maxPixelSize <= 0 || longest <= maxPixelSize) return new int[]{width, height};
    double scale = (double) maxPixelSize / longest;
    int targetWidth = width >= height ? maxPixelSize : (int) Math.max(1, Math.min(maxPixelSize, Math.round(width * scale)));
    int targetHeight = height > width ? maxPixelSize : (int) Math.max(1, Math.min(maxPixelSize, Math.round(height * scale)));
    return new int[]{targetWidth, targetHeight};
  }

  /** A short format name for {@link HeifImageInfo#typeIdentifier()}: the HEIF major brand, e.g. {@code heic}. */
  static String typeIdentifier(byte[] data, String containerFormat) {
    if (Guids.GUID_ContainerFormatHeif.equals(containerFormat)) {
      if (data.length >= 12) {
        String brand = new String(data, 8, 4, StandardCharsets.ISO_8859_1).trim();
        if (!brand.isEmpty() && brand.chars().allMatch(c -> c > 0x20 && c < 0x7F)) return brand.toLowerCase(Locale.ROOT);
      }
      return "heif";
    }
    if (Guids.GUID_ContainerFormatPng.equals(containerFormat)) return "png";
    if (Guids.GUID_ContainerFormatJpeg.equals(containerFormat)) return "jpeg";
    if (Guids.GUID_ContainerFormatTiff.equals(containerFormat)) return "tiff";
    if (Guids.GUID_ContainerFormatBmp.equals(containerFormat)) return "bmp";
    if (Guids.GUID_ContainerFormatGif.equals(containerFormat)) return "gif";
    return "wic:" + containerFormat;
  }

  /** Whether a failed {@code CreateDecoder}/{@code CreateDecoderFromStream} means that WIC has no usable HEIF decoder. */
  static boolean isHeifDecoderMissing(int hresult) {
    return hresult == Hresult.WINCODEC_ERR_COMPONENTINITIALIZEFAILURE || hresult == Hresult.WINCODEC_ERR_COMPONENTNOTFOUND
           || hresult == Hresult.WINCODEC_ERR_UNKNOWNIMAGEFORMAT || hresult == Hresult.REGDB_E_CLASSNOTREG;
  }

  /** Whether a failed decode means that the HEVC codec (Media Foundation transform) is missing. */
  static boolean isHevcDecoderMissing(int hresult) {
    return hresult == Hresult.MF_E_TOPO_CODEC_NOT_FOUND;
  }

  /**
   * One decode: COM initialized on this thread, and the COM objects and native buffers to release, in that nesting.
   * Not thread-safe; used on one thread from creation to {@link #close()}.
   */
  static final class Session implements AutoCloseable {
    private final WinApi api;
    private final boolean uninitialize;
    private final ArrayDeque<Long> owned = new ArrayDeque<>();
    private final ArrayDeque<Long> buffers = new ArrayDeque<>();
    private long factory;

    Session(WinApi api) throws WicException {
      this.api = api;
      int hr = api.coInitializeEx(COINIT_MULTITHREADED);
      if (hr == Hresult.S_OK || hr == Hresult.S_FALSE) {
        uninitialize = true; // every successful call, S_FALSE included, needs its CoUninitialize
      }
      else if (hr == Hresult.RPC_E_CHANGED_MODE) {
        uninitialize = false; // this thread is in a single-threaded apartment that belongs to someone else
      }
      else {
        throw new WicException("CoInitializeEx", hr);
      }
    }

    /** Registers a COM object for {@link #close()}; returns it. */
    long own(long object) {
      if (object != 0) owned.push(object);
      return object;
    }

    /** Releases {@code object} now (it must have been {@link #own owned}). */
    void releaseNow(long object) {
      if (object != 0 && owned.removeFirstOccurrence(object)) api.release(object);
    }

    /** {@code malloc} that is freed on {@link #close()}. */
    long allocate(long size) throws IOException {
      long buffer = api.malloc(size);
      if (buffer == 0) throw new IOException("Cannot allocate " + size + " bytes of native memory");
      buffers.push(buffer);
      return buffer;
    }

    void check(int hr, String call) throws WicException {
      if (Hresult.failed(hr)) throw new WicException(call, hr);
    }

    long factory() throws WicException {
      if (factory == 0) {
        long[] result = new long[1];
        int hr = api.coCreateInstance(Guids.CLSID_WICImagingFactory, CLSCTX_INPROC_SERVER, Guids.IID_IWICImagingFactory, result);
        own(result[0]);
        check(hr, "CoCreateInstance(CLSID_WICImagingFactory)");
        if (result[0] == 0) throw new WicException("CoCreateInstance(CLSID_WICImagingFactory)", Hresult.E_POINTER);
        factory = result[0];
      }
      return factory;
    }

    /** Creates an object with a factory method; {@code creator} returns the {@code HRESULT}. */
    long create(String call, Creator creator) throws WicException {
      long[] result = new long[1];
      int hr = creator.create(result);
      own(result[0]);
      check(hr, call);
      if (result[0] == 0) throw new WicException(call, Hresult.E_POINTER);
      return result[0];
    }

    Opened open(byte[] data, boolean requireHeif) throws IOException {
      long factory = factory();
      long stream = own(api.shCreateMemStream(data));
      if (stream == 0) throw new IOException("SHCreateMemStream failed for " + data.length + " bytes");

      long[] result = new long[1];
      int hr = api.createDecoderFromStream(factory, stream, WICDecodeMetadataCacheOnDemand, result);
      long decoder = own(result[0]);
      if (Hresult.failed(hr) && requireHeif && isHeifDecoderMissing(hr)) {
        throw new WicException("IWICImagingFactory::CreateDecoderFromStream (no HEIF decoder: is the HEIF Image "
                               + "Extension installed?)", hr);
      }
      check(hr, "IWICImagingFactory::CreateDecoderFromStream");
      if (decoder == 0) throw new WicException("IWICImagingFactory::CreateDecoderFromStream", Hresult.E_POINTER);

      String[] container = new String[1];
      check(api.getContainerFormat(decoder, container), "IWICBitmapDecoder::GetContainerFormat");
      boolean heif = Guids.GUID_ContainerFormatHeif.equals(container[0]);
      if (requireHeif && !heif) throw new IOException("WIC did not read the data as HEIF (container format " + container[0] + ")");

      int[] count = new int[1];
      check(api.getFrameCount(decoder, count), "IWICBitmapDecoder::GetFrameCount");
      if (count[0] < 1) throw new IOException("No image found (WIC frame count " + count[0] + ")");
      long frame = create("IWICBitmapDecoder::GetFrame", out -> api.getFrame(decoder, 0, out));

      int[] size = new int[2];
      check(api.getSize(frame, size), "IWICBitmapFrameDecode::GetSize");
      if (size[0] <= 0 || size[1] <= 0) throw new IOException("Invalid image size " + size[0] + "x" + size[1]);
      String[] pixelFormat = new String[1];
      check(api.getPixelFormat(frame, pixelFormat), "IWICBitmapFrameDecode::GetPixelFormat");

      Alpha alpha = Alpha.NONE;
      long transform = 0;
      if (heif) {
        long[] query = new long[1];
        if (Hresult.succeeded(api.queryInterface(frame, Guids.IID_IWICBitmapSourceTransform, query)) && query[0] != 0) {
          transform = own(query[0]);
          String[] closest = {Guids.GUID_WICPixelFormat8bppAlpha};
          if (Hresult.succeeded(api.getClosestPixelFormat(transform, closest))
              && Guids.GUID_WICPixelFormat8bppAlpha.equals(closest[0])) {
            alpha = Alpha.PLANE;
          }
          else {
            releaseNow(transform);
            transform = 0;
          }
        }
      }
      if (alpha == Alpha.NONE && hasTransparency(pixelFormat[0])) {
        alpha = Alpha.IN_PIXELS; // other formats; a HEIF decoder whose frames carry alpha themselves
      }
      return new Opened(container[0], count[0], frame, size[0], size[1], pixelFormat[0], alpha, transform, orientation(frame));
    }

    /** {@code IWICPixelFormatInfo2::SupportsTransparency} of a pixel format; {@code false} if unknown. */
    private boolean hasTransparency(String pixelFormat) {
      long[] info = new long[1];
      try {
        if (Hresult.failed(api.createComponentInfo(factory, pixelFormat, info)) || own(info[0]) == 0) return false;
        long[] info2 = new long[1];
        if (Hresult.failed(api.queryInterface(info[0], Guids.IID_IWICPixelFormatInfo2, info2)) || own(info2[0]) == 0) {
          return false;
        }
        int[] supported = new int[1];
        return Hresult.succeeded(api.supportsTransparency(info2[0], supported)) && supported[0] != 0;
      }
      catch (RuntimeException e) {
        return false;
      }
    }

    /** {@code System.Photo.Orientation} of the frame (1..8), {@code 1} if the frame has no such metadata. */
    private int orientation(long frame) {
      long[] reader = new long[1];
      if (Hresult.failed(api.getMetadataQueryReader(frame, reader)) || reader[0] == 0) {
        own(reader[0]);
        return 1; // e.g. WINCODEC_ERR_UNSUPPORTEDOPERATION: the format has no metadata
      }
      long queryReader = own(reader[0]);
      try {
        long[] value = new long[1];
        int hr = api.getMetadataInteger(queryReader, ORIENTATION_POLICY, value);
        return hr == Hresult.S_OK && value[0] >= 1 && value[0] <= 8 ? (int) value[0] : 1;
      }
      finally {
        releaseNow(queryReader);
      }
    }

    /** Scales, converts and copies the frame into a new image (orientation not applied yet). */
    BufferedImage render(Opened opened, int maxPixelSize, int stripPixels) throws IOException {
      long factory = factory();
      int[] target = targetSize(opened.width, opened.height, maxPixelSize);
      int width = target[0], height = target[1];
      boolean scaled = width != opened.width || height != opened.height;
      if (scaled && opened.alpha == Alpha.PLANE && (long) opened.width * opened.height <= ALPHA_WEIGHTED_MAX_PIXELS) {
        BufferedImage image = renderAlphaWeighted(opened, maxPixelSize);
        if (image != null) return image;
      }
      BufferedImage image = PixelPipeline.newImage(width, height, opened.alpha != Alpha.NONE);

      long source = opened.frame;
      if (scaled) {
        long scaler = create("IWICImagingFactory::CreateBitmapScaler", out -> api.createBitmapScaler(factory, out));
        check(api.initializeBitmapScaler(scaler, source, width, height, WICBitmapInterpolationModeFant),
              "IWICBitmapScaler::Initialize");
        source = scaler;
      }
      String format = opened.alpha == Alpha.IN_PIXELS ? Guids.GUID_WICPixelFormat32bppBGRA : Guids.GUID_WICPixelFormat32bppBGR;
      long bitmap = decodedBitmap(source, format, width, height);

      byte[] alphaPlane = opened.alpha == Alpha.PLANE ? alphaPlane(opened.transform, width, height) : null;

      int stripRows = PixelPipeline.stripRows(width, height, stripPixels);
      int stride = 4 * width;
      long buffer = allocate((long) stride * stripRows);
      int[] pixels = new int[width * stripRows];
      for (int y0 = 0; y0 < height; y0 += stripRows) {
        int rows = Math.min(stripRows, height - y0);
        check(api.copyPixels(bitmap, 0, y0, width, rows, stride, stride * rows, buffer), "IWICBitmap::CopyPixels");
        api.readInts(buffer, pixels, width * rows); // little-endian BGRA/BGRX == int 0xAARRGGBB
        if (alphaPlane != null) {
          for (int i = 0, a = y0 * width; i < width * rows; i++, a++) {
            pixels[i] = (alphaPlane[a] & 0xFF) << 24 | (pixels[i] & 0x00FFFFFF);
          }
        }
        else if (opened.alpha == Alpha.NONE) {
          for (int i = 0; i < width * rows; i++) pixels[i] &= 0x00FFFFFF; // the fourth byte of 32bppBGR is undefined
        }
        PixelPipeline.writeArgbRows(image, y0, rows, pixels, false); // straight alpha
      }
      return image;
    }

    /**
     * {@code source} converted to {@code format} and decoded (and scaled and converted) once, with
     * {@code WICBitmapCacheOnLoad}: copying strips of the bitmap is then a plain copy. It must be {@code width x height}.
     */
    private long decodedBitmap(long source, String format, int width, int height) throws IOException {
      long factory = factory();
      long converter = create("IWICImagingFactory::CreateFormatConverter", out -> api.createFormatConverter(factory, out));
      check(api.initializeFormatConverter(converter, source, format), "IWICFormatConverter::Initialize");
      long bitmap = create("IWICImagingFactory::CreateBitmapFromSource",
                           out -> api.createBitmapFromSource(factory, converter, WICBitmapCacheOnLoad, out));
      int[] bitmapSize = new int[2];
      check(api.getSize(bitmap, bitmapSize), "IWICBitmap::GetSize");
      if (bitmapSize[0] != width || bitmapSize[1] != height) {
        throw new IOException("WIC produced a " + bitmapSize[0] + "x" + bitmapSize[1] + " image instead of " + width + "x" + height);
      }
      return bitmap;
    }

    /**
     * An image with an alpha plane, downscaled to {@code maxPixelSize} with alpha-weighted averaging: the full-size
     * frame ({@code 32bppBGR}) and alpha plane, both in native memory, are read strip by strip as straight RGBA and
     * reduced by {@link PlaneConverter}, so the Java heap holds only a strip and the result. {@code null} if the decoder
     * cannot produce the alpha plane at full size (the caller then scales with WIC).
     */
    private @Nullable BufferedImage renderAlphaWeighted(Opened opened, int maxPixelSize) throws IOException {
      int width = opened.width, height = opened.height;
      long alpha = fullSizeAlphaPlane(opened.transform, width, height);
      if (alpha == 0) return null;
      long bitmap = decodedBitmap(opened.frame, Guids.GUID_WICPixelFormat32bppBGR, width, height);
      int stride = 4 * width;
      long[] buffer = {0};
      int[] bufferRows = {0};
      byte[][] alphaRows = {null};
      return PlaneConverter.convert(width, height, stride, ByteLayout.RGBA, false, true, maxPixelSize, (y0, rows, strip) -> {
        if (rows > bufferRows[0]) { // the first strip is the largest
          buffer[0] = allocate((long) stride * rows);
          bufferRows[0] = rows;
          alphaRows[0] = new byte[width * rows];
        }
        check(api.copyPixels(bitmap, 0, y0, width, rows, stride, stride * rows, buffer[0]), "IWICBitmap::CopyPixels");
        api.readBytes(buffer[0], strip, stride * rows);
        api.readBytes(alpha + (long) y0 * width, alphaRows[0], width * rows);
        byte[] a = alphaRows[0];
        for (int i = 0, at = 0; i < width * rows; i++, at += 4) {
          byte blue = strip[at]; // BGRX to RGBA, with the alpha of the plane
          strip[at] = strip[at + 2];
          strip[at + 2] = blue;
          strip[at + 3] = a[i];
        }
      });
    }

    /**
     * The {@code 8bppAlpha} plane of the HEIF image at its full size {@code width x height}, row-major, in native memory
     * that is freed on {@link #close()}; {@code 0} if the decoder offers the plane only at another size.
     */
    private long fullSizeAlphaPlane(long transform, int width, int height) throws IOException {
      int[] size = {width, height};
      if (Hresult.succeeded(api.getClosestSize(transform, size)) && (size[0] != width || size[1] != height)) return 0;
      long bytes = (long) width * height;
      if (bytes > Integer.MAX_VALUE - 16) return 0;
      long buffer = allocate(bytes);
      check(api.copyTransformedPixels(transform, width, height, Guids.GUID_WICPixelFormat8bppAlpha,
                                      WICBitmapTransformRotate0, width, (int) bytes, buffer),
            "IWICBitmapSourceTransform::CopyPixels(8bppAlpha)");
      return buffer;
    }

    /** The {@code 8bppAlpha} plane of the HEIF image at {@code width x height}, row-major. */
    private byte[] alphaPlane(long transform, int width, int height) throws IOException {
      int[] size = {width, height};
      if (Hresult.failed(api.getClosestSize(transform, size)) || size[0] <= 0 || size[1] <= 0) {
        size[0] = width;
        size[1] = height;
      }
      long bytes = (long) size[0] * size[1];
      if (bytes > Integer.MAX_VALUE - 16) throw new IOException("Alpha plane too large: " + size[0] + "x" + size[1]);
      long buffer = allocate(bytes);
      check(api.copyTransformedPixels(transform, size[0], size[1], Guids.GUID_WICPixelFormat8bppAlpha,
                                      WICBitmapTransformRotate0, size[0], (int) bytes, buffer),
            "IWICBitmapSourceTransform::CopyPixels(8bppAlpha)");
      byte[] plane = new byte[(int) bytes];
      api.readBytes(buffer, plane, plane.length);
      return size[0] == width && size[1] == height ? plane : resample(plane, size[0], size[1], width, height);
    }

    /**
     * The ICC profile of the first profile-type color context of the frame, or {@code null} (no context, only an EXIF
     * color space, whose value 1 means sRGB, or a failure: the colors are then used as they are).
     */
    @Nullable
    byte[] iccProfile(long frame) {
      try {
        int[] count = new int[1];
        if (Hresult.failed(api.getColorContexts(frame, new long[0], count)) || count[0] <= 0) return null;
        long factory = factory();
        long[] contexts = new long[Math.min(count[0], 16)];
        for (int i = 0; i < contexts.length; i++) {
          contexts[i] = create("IWICImagingFactory::CreateColorContext", out -> api.createColorContext(factory, out));
        }
        int[] actual = new int[1];
        if (Hresult.failed(api.getColorContexts(frame, contexts, actual))) return null;
        for (int i = 0; i < Math.min(actual[0], contexts.length); i++) {
          int[] type = new int[1];
          if (Hresult.failed(api.getColorContextType(contexts[i], type)) || type[0] != WICColorContextProfile) continue;
          int[] length = new int[1];
          if (Hresult.failed(api.getProfileBytes(contexts[i], null, length)) || length[0] <= 0 || length[0] > 64 << 20) {
            continue;
          }
          byte[] profile = new byte[length[0]];
          if (Hresult.succeeded(api.getProfileBytes(contexts[i], profile, length)) && length[0] == profile.length) {
            return profile;
          }
        }
        return null;
      }
      catch (WicException e) {
        return null;
      }
    }

    @Override
    public void close() {
      try {
        while (!owned.isEmpty()) {
          long object = owned.pop();
          try {
            api.release(object);
          }
          catch (RuntimeException e) {
            // keep releasing the others
          }
        }
        while (!buffers.isEmpty()) api.free(buffers.pop());
      }
      finally {
        if (uninitialize) api.coUninitialize();
      }
    }
  }

  /** A factory call that writes the created object into {@code out[0]} and returns the {@code HRESULT}. */
  interface Creator {
    int create(long[] out);
  }

  /** Bilinear resampling of an 8-bit plane (the rare case that the decoder cannot produce the alpha plane at size). */
  static byte[] resample(byte[] plane, int width, int height, int targetWidth, int targetHeight) {
    byte[] result = new byte[targetWidth * targetHeight];
    double sx = (double) width / targetWidth, sy = (double) height / targetHeight;
    for (int y = 0; y < targetHeight; y++) {
      double fy = Math.max(0, (y + 0.5) * sy - 0.5);
      int y0 = Math.min(height - 1, (int) fy), y1 = Math.min(height - 1, y0 + 1);
      double wy = fy - y0;
      for (int x = 0; x < targetWidth; x++) {
        double fx = Math.max(0, (x + 0.5) * sx - 0.5);
        int x0 = Math.min(width - 1, (int) fx), x1 = Math.min(width - 1, x0 + 1);
        double wx = fx - x0;
        double top = (plane[y0 * width + x0] & 0xFF) * (1 - wx) + (plane[y0 * width + x1] & 0xFF) * wx;
        double bottom = (plane[y1 * width + x0] & 0xFF) * (1 - wx) + (plane[y1 * width + x1] & 0xFF) * wx;
        result[y * targetWidth + x] = (byte) Math.round(top * (1 - wy) + bottom * wy);
      }
    }
    return result;
  }
}
