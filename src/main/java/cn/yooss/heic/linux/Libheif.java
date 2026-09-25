package cn.yooss.heic.linux;

import cn.yooss.heic.backend.jna.JnaLibraries;
import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The part of the libheif C API ({@code libheif.so.1}) that the Linux backend uses, called through the IDE's JNA
 * under the rules of {@link JnaLibraries}: only {@link NativeLibrary} and {@code Function.invoke*} with JDK-typed
 * arguments, native memory from {@link Native#malloc}; no {@code Library} interface and no {@code Structure}.
 * <p>
 * Every function used exists in libheif 1.6 (Ubuntu 20.04) and later with an unchanged signature (the library has kept
 * its soname {@code libheif.so.1} and its ABI since 1.0); newer optional functions are looked up individually and used
 * only when present: {@code heif_init} (1.13), {@code heif_image_handle_is_premultiplied_alpha} (1.12),
 * {@code heif_load_plugins} (1.14), {@code heif_get_decoder_descriptors} (1.15), {@code heif_get_plugin_directories}
 * (1.17) and {@code heif_image_get_plane_readonly2} (1.20).
 * <p>
 * <b>{@code struct heif_error} returned by value.</b> Most functions return {@code struct heif_error {int code;
 * int subcode; const char* message;}}, 16 bytes, by value. Both supported ABIs return such a struct in two
 * general-purpose registers instead of through a hidden result pointer:
 * <ul>
 *   <li>x86-64 System V: a 16-byte aggregate whose two eightbytes are both of class INTEGER is returned in
 *   {@code RAX} (bytes 0-7: {@code code}, {@code subcode}) and {@code RDX} (bytes 8-15: {@code message});</li>
 *   <li>AArch64 (AAPCS64, also Apple arm64): a composite type of at most 16 bytes that is not a homogeneous
 *   floating-point aggregate is returned in {@code X0} (bytes 0-7) and {@code X1} (bytes 8-15).</li>
 * </ul>
 * The caller passes no hidden pointer in either case, so calling such a function as if it returned an {@code int64_t}
 * ({@link Function#invokeLong}) is ABI-compatible: the result is the first register, i.e. bytes 0-7 of the struct,
 * and the second (caller-saved) register is ignored. On these little-endian targets {@code code} is the low and
 * {@code subcode} the high 32 bits ({@link LibheifException#check}). The message pointer is lost; the codes are
 * turned into text by {@link LibheifException}. Other architectures (32-bit ARM, s390x, ...) return the struct in
 * memory, so the backend refuses them ({@link #isSupportedPlatform}). {@code LibheifAbiTest} checks the decoding
 * against real errors on x86-64 and AArch64 in CI.
 * <p>
 * Thread-safe: libheif contexts are independent, the {@code Function} objects are immutable.
 */
final class Libheif {
  static final int heif_colorspace_RGB = 1;
  static final int heif_chroma_interleaved_RGB = 10;
  static final int heif_chroma_interleaved_RGBA = 11;
  static final int heif_channel_interleaved = 10;
  static final int heif_compression_HEVC = 1;

  private static final long MAX_COLOR_PROFILE = 16L << 20;

  /** What the library was opened with (a file name such as {@code libheif.so.1}, or a path). */
  final String source;
  private final NativeLibrary library;

  private final Function getVersion;
  private final Function getVersionNumber;
  private final Function contextAlloc;
  private final Function contextFree;
  private final Function readFromMemoryWithoutCopy;
  private final Function numberOfTopLevelImages;
  private final Function listOfTopLevelImageIds;
  private final Function primaryImageId;
  private final Function primaryImageHandle;
  private final Function handleRelease;
  private final Function handleWidth;
  private final Function handleHeight;
  private final Function handleHasAlpha;
  private final Function handleLumaBits;
  private final Function handleNumberOfThumbnails;
  private final Function handleThumbnailIds;
  private final Function handleThumbnail;
  private final Function handleRawColorProfileSize;
  private final Function handleRawColorProfile;
  private final Function decodeImage;
  private final Function imageWidth;
  private final Function imageHeight;
  private final Function imagePlaneReadonly;
  private final Function imageRelease;
  private final Function haveDecoderForFormat;
  // Optional (newer versions)
  private final @Nullable Function init;
  private final @Nullable Function handleIsPremultipliedAlpha;
  private final @Nullable Function imagePlaneReadonly2;
  private final @Nullable Function loadPlugins;
  private final @Nullable Function pluginDirectories;
  private final @Nullable Function freePluginDirectories;
  private final @Nullable Function decoderDescriptors;
  private final @Nullable Function decoderDescriptorName;

  private Libheif(String source, NativeLibrary library) {
    this.source = source;
    this.library = library;
    getVersion = function("heif_get_version");
    getVersionNumber = function("heif_get_version_number");
    contextAlloc = function("heif_context_alloc");
    contextFree = function("heif_context_free");
    readFromMemoryWithoutCopy = function("heif_context_read_from_memory_without_copy");
    numberOfTopLevelImages = function("heif_context_get_number_of_top_level_images");
    listOfTopLevelImageIds = function("heif_context_get_list_of_top_level_image_IDs");
    primaryImageId = function("heif_context_get_primary_image_ID");
    primaryImageHandle = function("heif_context_get_primary_image_handle");
    handleRelease = function("heif_image_handle_release");
    handleWidth = function("heif_image_handle_get_width");
    handleHeight = function("heif_image_handle_get_height");
    handleHasAlpha = function("heif_image_handle_has_alpha_channel");
    handleLumaBits = function("heif_image_handle_get_luma_bits_per_pixel");
    handleNumberOfThumbnails = function("heif_image_handle_get_number_of_thumbnails");
    handleThumbnailIds = function("heif_image_handle_get_list_of_thumbnail_IDs");
    handleThumbnail = function("heif_image_handle_get_thumbnail");
    handleRawColorProfileSize = function("heif_image_handle_get_raw_color_profile_size");
    handleRawColorProfile = function("heif_image_handle_get_raw_color_profile");
    decodeImage = function("heif_decode_image");
    imageWidth = function("heif_image_get_width");
    imageHeight = function("heif_image_get_height");
    imagePlaneReadonly = function("heif_image_get_plane_readonly");
    imageRelease = function("heif_image_release");
    haveDecoderForFormat = function("heif_have_decoder_for_format");
    init = optionalFunction("heif_init");
    handleIsPremultipliedAlpha = optionalFunction("heif_image_handle_is_premultiplied_alpha");
    imagePlaneReadonly2 = optionalFunction("heif_image_get_plane_readonly2");
    loadPlugins = optionalFunction("heif_load_plugins");
    pluginDirectories = optionalFunction("heif_get_plugin_directories");
    freePluginDirectories = pluginDirectories != null ? optionalFunction("heif_free_plugin_directories") : null;
    decoderDescriptors = optionalFunction("heif_get_decoder_descriptors");
    decoderDescriptorName = decoderDescriptors != null ? optionalFunction("heif_decoder_descriptor_get_name") : null;
  }

  /**
   * Opens libheif ({@code dlopen} through JNA) and resolves the functions the backend needs.
   *
   * @param nameOrPath a file name such as {@code libheif.so.1} (found by the dynamic linker's search), a short name
   *                   such as {@code heif} (JNA also searches {@code libheif.so*}) or an absolute path
   * @throws UnsatisfiedLinkError if the library cannot be loaded or lacks a required function
   */
  static @NotNull Libheif open(@NotNull String nameOrPath) {
    return new Libheif(nameOrPath, JnaLibraries.open(nameOrPath));
  }

  /**
   * Whether {@code heif_error} results can be read on this platform (see the class comment): x86-64 or AArch64,
   * little-endian.
   */
  static boolean isSupportedPlatform(@NotNull String arch) {
    String a = arch.toLowerCase(Locale.ROOT);
    boolean abi = a.equals("amd64") || a.equals("x86_64") || a.equals("aarch64") || a.equals("arm64");
    return abi && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
  }

  private Function function(String name) {
    return library.getFunction(name);
  }

  private @Nullable Function optionalFunction(String name) {
    try {
      return library.getFunction(name);
    }
    catch (UnsatisfiedLinkError e) {
      return null;
    }
  }

  // ---------------------------------------------------------------- library

  /** {@code heif_get_version()}, e.g. {@code "1.17.6"}. */
  @NotNull String version() {
    String version = cString(getVersion.invokeLong(new Object[0]));
    return version != null ? version : "?";
  }

  /** {@code heif_get_version_number()}: {@code 0xMMmmpp00}, e.g. {@code 0x01110600} for 1.17.6. */
  int versionNumber() {
    return getVersionNumber.invokeInt(new Object[0]);
  }

  /** The major version: 1 for every libheif with the soname {@code libheif.so.1}. */
  int majorVersion() {
    return versionNumber() >>> 24;
  }

  /** Version as a comparable {@code major * 10000 + minor * 100 + patch}, e.g. 11706 for 1.17.6. */
  int comparableVersion() {
    int v = versionNumber();
    return (v >>> 24) * 10000 + ((v >>> 16) & 0xFF) * 100 + ((v >>> 8) & 0xFF);
  }

  boolean hasInit() {
    return init != null;
  }

  /**
   * {@code heif_init(NULL)} (libheif 1.13+): registers the built-in codecs and, since 1.14, loads the codec plugins
   * (such as {@code libheif-libde265.so}) from the plugin directory. Reference counted; the backend calls it once and
   * never calls {@code heif_deinit} (see {@link LibheifHeifBackend}).
   *
   * @throws LibheifException if libheif reports an error (e.g. a plugin that cannot be loaded)
   */
  void init() throws LibheifException {
    if (init != null) LibheifException.check(init.invokeLong(new Object[]{0L}), "heif_init");
  }

  /** {@code heif_have_decoder_for_format(heif_compression_HEVC)}. */
  boolean hasHevcDecoder() {
    return haveDecoderForFormat.invokeInt(new Object[]{heif_compression_HEVC}) != 0;
  }

  /** Names of the registered HEVC decoders (libheif 1.15+, else empty), e.g. {@code "libde265 HEVC decoder, version 1.0.15"}. */
  @NotNull List<String> hevcDecoderNames() {
    List<String> names = new ArrayList<>();
    if (decoderDescriptors == null || decoderDescriptorName == null) return names;
    long[] descriptors = new long[8];
    int count = decoderDescriptors.invokeInt(new Object[]{heif_compression_HEVC, descriptors, descriptors.length});
    for (int i = 0; i < Math.min(count, descriptors.length); i++) {
      String name = cString(decoderDescriptorName.invokeLong(new Object[]{descriptors[i]}));
      if (name != null) names.add(name);
    }
    return names;
  }

  /** {@code heif_get_plugin_directories()} (libheif 1.17+), or {@code null} if this version cannot tell. */
  @Nullable List<String> pluginDirectories() {
    if (pluginDirectories == null) return null;
    long array = pluginDirectories.invokeLong(new Object[0]);
    List<String> directories = new ArrayList<>();
    if (array == 0) return directories;
    try {
      Pointer entries = new Pointer(array);
      for (int i = 0; i < 64; i++) {
        long entry = entries.getLong((long) i * Native.POINTER_SIZE);
        if (entry == 0) break;
        String directory = cString(entry);
        if (directory != null) directories.add(directory);
      }
    }
    finally {
      if (freePluginDirectories != null) freePluginDirectories.invokeVoid(new Object[]{array});
    }
    return directories;
  }

  boolean canLoadPlugins() {
    return loadPlugins != null;
  }

  /**
   * {@code heif_load_plugins(directory, NULL, NULL, 0)} (libheif 1.14+): loads the plugins that were installed after
   * {@code heif_init}; plugins that are loaded already are not registered twice.
   */
  void loadPlugins(@NotNull String directory) throws LibheifException {
    if (loadPlugins == null) return;
    LibheifException.check(loadPlugins.invokeLong(new Object[]{JnaLibraries.utf8z(directory), 0L, 0L, 0}),
                           "heif_load_plugins");
  }

  // ---------------------------------------------------------------- context

  /** {@code heif_context_alloc()}; 0 on failure. */
  long contextAlloc() {
    return contextAlloc.invokeLong(new Object[0]);
  }

  void contextFree(long context) {
    if (context != 0) contextFree.invokeVoid(new Object[]{context});
  }

  /** The data at {@code address} must stay valid until the context is freed (and all its handles are released). */
  void readFromMemoryWithoutCopy(long context, long address, long size) throws LibheifException {
    LibheifException.check(readFromMemoryWithoutCopy.invokeLong(new Object[]{context, address, size, 0L}),
                           "heif_context_read_from_memory_without_copy");
  }

  int numberOfTopLevelImages(long context) {
    return numberOfTopLevelImages.invokeInt(new Object[]{context});
  }

  /** Index of the primary image among the top-level images, or 0 if it cannot be determined. */
  int primaryImageIndex(long context, int topLevelImages) {
    if (topLevelImages <= 1) return 0;
    int[] primary = new int[1];
    try {
      LibheifException.check(primaryImageId.invokeLong(new Object[]{context, primary}), "heif_context_get_primary_image_ID");
    }
    catch (LibheifException e) {
      return 0;
    }
    int[] ids = new int[Math.min(topLevelImages, 4096)];
    int n = listOfTopLevelImageIds.invokeInt(new Object[]{context, ids, ids.length});
    for (int i = 0; i < Math.min(n, ids.length); i++) {
      if (ids[i] == primary[0]) return i;
    }
    return 0;
  }

  /** {@code heif_context_get_primary_image_handle}: a handle that must be {@linkplain #release released}. */
  long primaryImageHandle(long context) throws LibheifException {
    long[] handle = new long[1];
    LibheifException.check(primaryImageHandle.invokeLong(new Object[]{context, handle}), "heif_context_get_primary_image_handle");
    if (handle[0] == 0) throw new LibheifException("heif_context_get_primary_image_handle", 0, 0);
    return handle[0];
  }

  // ---------------------------------------------------------------- image handles

  void release(long handle) {
    if (handle != 0) handleRelease.invokeVoid(new Object[]{handle});
  }

  /** Width with the transformations ({@code irot}, {@code imir}, {@code clap}) applied, as decoded. */
  int width(long handle) {
    return handleWidth.invokeInt(new Object[]{handle});
  }

  /** Height with the transformations applied, as decoded. */
  int height(long handle) {
    return handleHeight.invokeInt(new Object[]{handle});
  }

  boolean hasAlpha(long handle) {
    return handleHasAlpha.invokeInt(new Object[]{handle}) != 0;
  }

  /** Whether the color channels are premultiplied by alpha (libheif 1.12+; {@code false} before). */
  boolean isPremultipliedAlpha(long handle) {
    return handleIsPremultipliedAlpha != null && handleIsPremultipliedAlpha.invokeInt(new Object[]{handle}) != 0;
  }

  /** Bits per luma sample as stored, or -1 if unknown. */
  int lumaBitsPerPixel(long handle) {
    return handleLumaBits.invokeInt(new Object[]{handle});
  }

  /** Item ids of the thumbnails of the image {@code handle}. */
  int @NotNull [] thumbnailIds(long handle) {
    int count = handleNumberOfThumbnails.invokeInt(new Object[]{handle});
    if (count <= 0) return new int[0];
    int[] ids = new int[Math.min(count, 64)];
    int n = handleThumbnailIds.invokeInt(new Object[]{handle, ids, ids.length});
    return n >= ids.length ? ids : Arrays.copyOf(ids, Math.max(0, n));
  }

  /** {@code heif_image_handle_get_thumbnail}: a handle that must be {@linkplain #release released}. */
  long thumbnail(long handle, int thumbnailId) throws LibheifException {
    long[] thumbnail = new long[1];
    LibheifException.check(handleThumbnail.invokeLong(new Object[]{handle, thumbnailId, thumbnail}),
                           "heif_image_handle_get_thumbnail");
    if (thumbnail[0] == 0) throw new LibheifException("heif_image_handle_get_thumbnail", 0, 0);
    return thumbnail[0];
  }

  /** The raw ICC profile ({@code colr} box of type {@code prof} or {@code rICC}), or {@code null}. */
  byte @Nullable [] iccProfile(long handle) throws LibheifException {
    long size = handleRawColorProfileSize.invokeLong(new Object[]{handle});
    if (size <= 0 || size > MAX_COLOR_PROFILE) return null;
    byte[] profile = new byte[(int) size];
    LibheifException.check(handleRawColorProfile.invokeLong(new Object[]{handle, profile}),
                           "heif_image_handle_get_raw_color_profile");
    return profile;
  }

  // ---------------------------------------------------------------- decoded images

  /**
   * {@code heif_decode_image(handle, &image, heif_colorspace_RGB, chroma, NULL)}: decodes with the default options,
   * which apply the transformations ({@code irot}, {@code imir}, {@code clap}). The interleaved RGB(A) chromas are
   * always 8 bits per sample (images with more bits are reduced). Returns an image that must be
   * {@linkplain #releaseImage released}.
   */
  long decode(long handle, boolean alpha) throws LibheifException {
    long[] image = new long[1];
    int chroma = alpha ? heif_chroma_interleaved_RGBA : heif_chroma_interleaved_RGB;
    LibheifException.check(decodeImage.invokeLong(new Object[]{handle, image, heif_colorspace_RGB, chroma, 0L}),
                           "heif_decode_image");
    if (image[0] == 0) throw new LibheifException("heif_decode_image", 0, 0);
    return image[0];
  }

  void releaseImage(long image) {
    if (image != 0) imageRelease.invokeVoid(new Object[]{image});
  }

  /** Width of the interleaved plane of a decoded image, or -1. */
  int planeWidth(long image) {
    return imageWidth.invokeInt(new Object[]{image, heif_channel_interleaved});
  }

  /** Height of the interleaved plane of a decoded image, or -1. */
  int planeHeight(long image) {
    return imageHeight.invokeInt(new Object[]{image, heif_channel_interleaved});
  }

  /**
   * Address of the interleaved plane (0 if there is none) and its stride in bytes ({@code stride[0]}); the memory
   * belongs to the image.
   */
  long plane(long image, long[] stride) {
    if (imagePlaneReadonly2 != null) {
      return imagePlaneReadonly2.invokeLong(new Object[]{image, heif_channel_interleaved, stride});
    }
    int[] intStride = new int[1];
    long address = imagePlaneReadonly.invokeLong(new Object[]{image, heif_channel_interleaved, intStride});
    stride[0] = intStride[0];
    return address;
  }

  // ---------------------------------------------------------------- native memory

  /** {@code size} bytes of native memory holding {@code data}; free with {@link #free}. */
  static long copyToNative(byte[] data) throws IOException {
    long address = Native.malloc(data.length);
    if (address == 0) throw new IOException("Cannot allocate " + data.length + " bytes of native memory");
    new Pointer(address).write(0, data, 0, data.length);
    return address;
  }

  static void free(long address) {
    if (address != 0) Native.free(address);
  }

  /** Copies {@code length} bytes from native memory. */
  static void read(long address, byte[] target, int length) {
    new Pointer(address).read(0, target, 0, length);
  }

  /** A NUL-terminated UTF-8 string at {@code address}, or {@code null} for NULL. */
  static @Nullable String cString(long address) {
    if (address == 0) return null;
    return new Pointer(address).getString(0, StandardCharsets.UTF_8.name());
  }

  @Override
  public String toString() {
    return "libheif " + version() + " (" + source + ")";
  }
}
