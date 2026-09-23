package cn.yooss.heic.mac;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Minimal FFM ({@code java.lang.foreign}) bindings for CoreFoundation, ImageIO.framework, CoreGraphics and
 * the Objective-C runtime.
 * <p>
 * This class is the lazy holder of all native state: the frameworks are looked up and every downcall handle is
 * created in its static initializer, which runs on the first decode only. Nothing in the sniffing or
 * registration code references it. If the lookup fails (not macOS, native access denied, ...) the class
 * initializer throws and callers see a {@link LinkageError}, which {@link HeicDecoder} turns into an
 * {@link java.io.IOException}.
 * <p>
 * C type mapping: {@code CFIndex}/{@code size_t}/{@code CFTypeID} -> {@code long}; {@code Boolean} (unsigned
 * char) -> {@code byte}; {@code uint32_t}/{@code int32_t} enums -> {@code int}; {@code CGFloat} -> {@code double};
 * {@code CGRect} is passed by value as a struct of four doubles; every {@code *Ref} is an address.
 * Constants: CFString/CFBoolean globals are pointers, so the value is read from the symbol address;
 * {@code kCFTypeDictionary*CallBacks} are structs, so the symbol address itself is passed.
 */
final class MacImageIO {
  private MacImageIO() {
  }

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup CORE_FOUNDATION = framework("CoreFoundation");
  private static final SymbolLookup IMAGE_IO = framework("ImageIO");
  private static final SymbolLookup CORE_GRAPHICS = framework("CoreGraphics");
  private static final SymbolLookup OBJC = library("/usr/lib/libobjc.A.dylib");

  /** {@code struct CGRect { CGPoint origin; CGSize size; }} flattened: x, y, width, height (CGFloat = double). */
  static final StructLayout CG_RECT = MemoryLayout.structLayout(
      JAVA_DOUBLE.withName("x"), JAVA_DOUBLE.withName("y"), JAVA_DOUBLE.withName("width"), JAVA_DOUBLE.withName("height"));

  // ---------------------------------------------------------------------------------------------- CoreFoundation
  private static final MethodHandle CF_DATA_CREATE =
      downcall(CORE_FOUNDATION, "CFDataCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
  private static final MethodHandle CF_RELEASE =
      downcall(CORE_FOUNDATION, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
  private static final MethodHandle CF_DICTIONARY_CREATE_MUTABLE =
      downcall(CORE_FOUNDATION, "CFDictionaryCreateMutable", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
  private static final MethodHandle CF_DICTIONARY_SET_VALUE =
      downcall(CORE_FOUNDATION, "CFDictionarySetValue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle CF_DICTIONARY_GET_VALUE =
      downcall(CORE_FOUNDATION, "CFDictionaryGetValue", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle CF_NUMBER_CREATE =
      downcall(CORE_FOUNDATION, "CFNumberCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
  private static final MethodHandle CF_NUMBER_GET_VALUE =
      downcall(CORE_FOUNDATION, "CFNumberGetValue", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, JAVA_LONG, ADDRESS));
  private static final MethodHandle CF_GET_TYPE_ID =
      downcall(CORE_FOUNDATION, "CFGetTypeID", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle CF_BOOLEAN_GET_VALUE =
      downcall(CORE_FOUNDATION, "CFBooleanGetValue", FunctionDescriptor.of(JAVA_BYTE, ADDRESS));
  private static final MethodHandle CF_STRING_GET_CSTRING =
      downcall(CORE_FOUNDATION, "CFStringGetCString", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));

  // ---------------------------------------------------------------------------------------------- ImageIO
  private static final MethodHandle CG_IMAGE_SOURCE_CREATE_WITH_DATA =
      downcall(IMAGE_IO, "CGImageSourceCreateWithData", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle CG_IMAGE_SOURCE_GET_COUNT =
      downcall(IMAGE_IO, "CGImageSourceGetCount", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle CG_IMAGE_SOURCE_GET_PRIMARY_IMAGE_INDEX =
      downcall(IMAGE_IO, "CGImageSourceGetPrimaryImageIndex", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle CG_IMAGE_SOURCE_GET_STATUS =
      downcall(IMAGE_IO, "CGImageSourceGetStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  private static final MethodHandle CG_IMAGE_SOURCE_GET_STATUS_AT_INDEX =
      downcall(IMAGE_IO, "CGImageSourceGetStatusAtIndex", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
  private static final MethodHandle CG_IMAGE_SOURCE_GET_TYPE =
      downcall(IMAGE_IO, "CGImageSourceGetType", FunctionDescriptor.of(ADDRESS, ADDRESS));
  private static final MethodHandle CG_IMAGE_SOURCE_COPY_PROPERTIES_AT_INDEX =
      downcall(IMAGE_IO, "CGImageSourceCopyPropertiesAtIndex", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
  private static final MethodHandle CG_IMAGE_SOURCE_CREATE_IMAGE_AT_INDEX =
      downcall(IMAGE_IO, "CGImageSourceCreateImageAtIndex", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
  private static final MethodHandle CG_IMAGE_SOURCE_CREATE_THUMBNAIL_AT_INDEX =
      downcall(IMAGE_IO, "CGImageSourceCreateThumbnailAtIndex", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

  // ---------------------------------------------------------------------------------------------- CoreGraphics
  private static final MethodHandle CG_IMAGE_GET_WIDTH =
      downcall(CORE_GRAPHICS, "CGImageGetWidth", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle CG_IMAGE_GET_HEIGHT =
      downcall(CORE_GRAPHICS, "CGImageGetHeight", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle CG_IMAGE_GET_ALPHA_INFO =
      downcall(CORE_GRAPHICS, "CGImageGetAlphaInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  private static final MethodHandle CG_IMAGE_CREATE_WITH_IMAGE_IN_RECT =
      downcall(CORE_GRAPHICS, "CGImageCreateWithImageInRect", FunctionDescriptor.of(ADDRESS, ADDRESS, CG_RECT));
  private static final MethodHandle CG_COLOR_SPACE_CREATE_WITH_NAME =
      downcall(CORE_GRAPHICS, "CGColorSpaceCreateWithName", FunctionDescriptor.of(ADDRESS, ADDRESS));
  private static final MethodHandle CG_BITMAP_CONTEXT_CREATE =
      downcall(CORE_GRAPHICS, "CGBitmapContextCreate",
               FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
  private static final MethodHandle CG_CONTEXT_SET_BLEND_MODE =
      downcall(CORE_GRAPHICS, "CGContextSetBlendMode", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
  private static final MethodHandle CG_CONTEXT_DRAW_IMAGE =
      downcall(CORE_GRAPHICS, "CGContextDrawImage", FunctionDescriptor.ofVoid(ADDRESS, CG_RECT, ADDRESS));

  // ---------------------------------------------------------------------------------------------- libobjc
  private static final MethodHandle OBJC_AUTORELEASE_POOL_PUSH =
      downcall(OBJC, "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS));
  private static final MethodHandle OBJC_AUTORELEASE_POOL_POP =
      downcall(OBJC, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS));

  // ---------------------------------------------------------------------------------------------- constants
  static final MemorySegment kCFBooleanTrue = pointerAt(CORE_FOUNDATION, "kCFBooleanTrue");
  static final MemorySegment kCFBooleanFalse = pointerAt(CORE_FOUNDATION, "kCFBooleanFalse");
  static final MemorySegment kCFTypeDictionaryKeyCallBacks = symbol(CORE_FOUNDATION, "kCFTypeDictionaryKeyCallBacks");
  static final MemorySegment kCFTypeDictionaryValueCallBacks = symbol(CORE_FOUNDATION, "kCFTypeDictionaryValueCallBacks");

  static final MemorySegment kCGImageSourceShouldCache = pointerAt(IMAGE_IO, "kCGImageSourceShouldCache");
  static final MemorySegment kCGImageSourceShouldCacheImmediately = pointerAt(IMAGE_IO, "kCGImageSourceShouldCacheImmediately");
  static final MemorySegment kCGImageSourceCreateThumbnailFromImageAlways =
      pointerAt(IMAGE_IO, "kCGImageSourceCreateThumbnailFromImageAlways");
  static final MemorySegment kCGImageSourceCreateThumbnailFromImageIfAbsent =
      pointerAt(IMAGE_IO, "kCGImageSourceCreateThumbnailFromImageIfAbsent");
  static final MemorySegment kCGImageSourceCreateThumbnailWithTransform =
      pointerAt(IMAGE_IO, "kCGImageSourceCreateThumbnailWithTransform");
  static final MemorySegment kCGImageSourceThumbnailMaxPixelSize = pointerAt(IMAGE_IO, "kCGImageSourceThumbnailMaxPixelSize");
  static final MemorySegment kCGImagePropertyPixelWidth = pointerAt(IMAGE_IO, "kCGImagePropertyPixelWidth");
  static final MemorySegment kCGImagePropertyPixelHeight = pointerAt(IMAGE_IO, "kCGImagePropertyPixelHeight");
  static final MemorySegment kCGImagePropertyOrientation = pointerAt(IMAGE_IO, "kCGImagePropertyOrientation");
  static final MemorySegment kCGImagePropertyDepth = pointerAt(IMAGE_IO, "kCGImagePropertyDepth");
  static final MemorySegment kCGImagePropertyHasAlpha = pointerAt(IMAGE_IO, "kCGImagePropertyHasAlpha");
  static final MemorySegment kCGColorSpaceSRGB = pointerAt(CORE_GRAPHICS, "kCGColorSpaceSRGB");

  static final long CF_NUMBER_TYPE_ID = typeId("CFNumberGetTypeID");
  static final long CF_BOOLEAN_TYPE_ID = typeId("CFBooleanGetTypeID");

  static final long kCFNumberSInt64Type = 4;
  static final int kCFStringEncodingUTF8 = 0x08000100;
  static final int kCGImageStatusComplete = 0;
  static final int kCGImageAlphaNone = 0;
  static final int kCGImageAlphaPremultipliedFirst = 2;
  static final int kCGImageAlphaNoneSkipLast = 5;
  static final int kCGImageAlphaNoneSkipFirst = 6;
  static final int kCGImageAlphaOnly = 7;
  static final int kCGBitmapAlphaInfoMask = 0x1F;
  static final int kCGBitmapByteOrder32Little = 2 << 12;
  static final int kCGBlendModeCopy = 17;

  // ---------------------------------------------------------------------------------------------- setup helpers
  private static SymbolLookup framework(String name) {
    return library("/System/Library/Frameworks/" + name + ".framework/" + name);
  }

  /**
   * Opens a system library by absolute path with {@code dlopen}. The {@code String} overload is used on purpose:
   * {@code libraryLookup(Path, Arena)} calls {@code Path.toRealPath()} first, which fails because since macOS 11
   * the system frameworks exist only in the dyld shared cache, not as files on disk. The libraries are part of
   * every process anyway and are never unloaded ({@link Arena#global()}); nothing here pins the plugin class loader.
   */
  private static SymbolLookup library(String absolutePath) {
    return SymbolLookup.libraryLookup(absolutePath, Arena.global());
  }

  private static MemorySegment symbol(SymbolLookup lookup, String name) {
    return lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
  }

  private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(symbol(lookup, name), descriptor);
  }

  /** Reads the pointer stored in a global variable (e.g. a {@code CFStringRef} constant). */
  private static MemorySegment pointerAt(SymbolLookup lookup, String name) {
    MemorySegment value = symbol(lookup, name).reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    if (isNull(value)) throw new UnsatisfiedLinkError("Constant is NULL: " + name);
    return value;
  }

  private static long typeId(String function) {
    try {
      return (long) LINKER.downcallHandle(symbol(CORE_FOUNDATION, function), FunctionDescriptor.of(JAVA_LONG)).invokeExact();
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static boolean isNull(MemorySegment segment) {
    return segment == null || segment.address() == 0L;
  }

  private static RuntimeException propagate(Throwable t) {
    if (t instanceof RuntimeException e) throw e;
    if (t instanceof Error e) throw e;
    throw new IllegalStateException(t);
  }

  // ---------------------------------------------------------------------------------------------- CoreFoundation
  static MemorySegment cfDataCreate(MemorySegment bytes, long length) {
    try {
      return (MemorySegment) CF_DATA_CREATE.invokeExact(MemorySegment.NULL, bytes, length);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  /** {@code CFRelease} crashes on NULL, so this is a no-op for NULL. */
  static void cfRelease(MemorySegment ref) {
    if (isNull(ref)) return;
    try {
      CF_RELEASE.invokeExact(ref);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static MemorySegment cfDictionaryCreateMutable(long capacity) {
    try {
      return (MemorySegment) CF_DICTIONARY_CREATE_MUTABLE.invokeExact(
          MemorySegment.NULL, capacity, kCFTypeDictionaryKeyCallBacks, kCFTypeDictionaryValueCallBacks);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static void cfDictionarySetValue(MemorySegment dictionary, MemorySegment key, MemorySegment value) {
    try {
      CF_DICTIONARY_SET_VALUE.invokeExact(dictionary, key, value);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  /** "Get rule": the result is not owned by the caller and must not be released. */
  static MemorySegment cfDictionaryGetValue(MemorySegment dictionary, MemorySegment key) {
    try {
      return (MemorySegment) CF_DICTIONARY_GET_VALUE.invokeExact(dictionary, key);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static MemorySegment cfNumberCreateSInt64(Arena arena, long value) {
    MemorySegment cell = arena.allocate(JAVA_LONG);
    cell.set(JAVA_LONG, 0, value);
    try {
      return (MemorySegment) CF_NUMBER_CREATE.invokeExact(MemorySegment.NULL, kCFNumberSInt64Type, cell);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  /** Returns the value of a CFNumber/CFBoolean, or {@code defaultValue} for NULL or other types. */
  static long cfLongValue(Arena arena, MemorySegment value, long defaultValue) {
    if (isNull(value)) return defaultValue;
    try {
      long type = (long) CF_GET_TYPE_ID.invokeExact(value);
      if (type == CF_BOOLEAN_TYPE_ID) {
        return (byte) CF_BOOLEAN_GET_VALUE.invokeExact(value) != 0 ? 1 : 0;
      }
      if (type != CF_NUMBER_TYPE_ID) return defaultValue;
      MemorySegment cell = arena.allocate(JAVA_LONG); // zero-initialized
      // Returns false only for a lossy conversion (e.g. from a float); the truncated value is stored anyway.
      byte ignoredExact = (byte) CF_NUMBER_GET_VALUE.invokeExact(value, kCFNumberSInt64Type, cell);
      return cell.get(JAVA_LONG, 0);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static String cfString(Arena arena, MemorySegment string) {
    if (isNull(string)) return null;
    MemorySegment buffer = arena.allocate(512);
    try {
      byte ok = (byte) CF_STRING_GET_CSTRING.invokeExact(string, buffer, buffer.byteSize(), kCFStringEncodingUTF8);
      return ok != 0 ? buffer.getString(0, StandardCharsets.UTF_8) : null;
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  // ---------------------------------------------------------------------------------------------- ImageIO
  static MemorySegment cgImageSourceCreateWithData(MemorySegment data, MemorySegment options) {
    try {
      return (MemorySegment) CG_IMAGE_SOURCE_CREATE_WITH_DATA.invokeExact(data, options);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static long cgImageSourceGetCount(MemorySegment source) {
    try {
      return (long) CG_IMAGE_SOURCE_GET_COUNT.invokeExact(source);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static long cgImageSourceGetPrimaryImageIndex(MemorySegment source) {
    try {
      return (long) CG_IMAGE_SOURCE_GET_PRIMARY_IMAGE_INDEX.invokeExact(source);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static int cgImageSourceGetStatus(MemorySegment source) {
    try {
      return (int) CG_IMAGE_SOURCE_GET_STATUS.invokeExact(source);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static int cgImageSourceGetStatusAtIndex(MemorySegment source, long index) {
    try {
      return (int) CG_IMAGE_SOURCE_GET_STATUS_AT_INDEX.invokeExact(source, index);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  /** "Get rule": the returned UTI string is not owned by the caller. */
  static MemorySegment cgImageSourceGetType(MemorySegment source) {
    try {
      return (MemorySegment) CG_IMAGE_SOURCE_GET_TYPE.invokeExact(source);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static MemorySegment cgImageSourceCopyPropertiesAtIndex(MemorySegment source, long index, MemorySegment options) {
    try {
      return (MemorySegment) CG_IMAGE_SOURCE_COPY_PROPERTIES_AT_INDEX.invokeExact(source, index, options);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static MemorySegment cgImageSourceCreateImageAtIndex(MemorySegment source, long index, MemorySegment options) {
    try {
      return (MemorySegment) CG_IMAGE_SOURCE_CREATE_IMAGE_AT_INDEX.invokeExact(source, index, options);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static MemorySegment cgImageSourceCreateThumbnailAtIndex(MemorySegment source, long index, MemorySegment options) {
    try {
      return (MemorySegment) CG_IMAGE_SOURCE_CREATE_THUMBNAIL_AT_INDEX.invokeExact(source, index, options);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  // ---------------------------------------------------------------------------------------------- CoreGraphics
  static long cgImageGetWidth(MemorySegment image) {
    try {
      return (long) CG_IMAGE_GET_WIDTH.invokeExact(image);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static long cgImageGetHeight(MemorySegment image) {
    try {
      return (long) CG_IMAGE_GET_HEIGHT.invokeExact(image);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static int cgImageGetAlphaInfo(MemorySegment image) {
    try {
      return (int) CG_IMAGE_GET_ALPHA_INFO.invokeExact(image);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  /** {@code rect} is a {@link #CG_RECT} in image pixel coordinates (origin top-left). */
  static MemorySegment cgImageCreateWithImageInRect(MemorySegment image, MemorySegment rect) {
    try {
      return (MemorySegment) CG_IMAGE_CREATE_WITH_IMAGE_IN_RECT.invokeExact(image, rect);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static MemorySegment cgColorSpaceCreateWithName(MemorySegment name) {
    try {
      return (MemorySegment) CG_COLOR_SPACE_CREATE_WITH_NAME.invokeExact(name);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static MemorySegment cgBitmapContextCreate(MemorySegment data, long width, long height, long bitsPerComponent,
                                             long bytesPerRow, MemorySegment colorSpace, int bitmapInfo) {
    try {
      return (MemorySegment) CG_BITMAP_CONTEXT_CREATE.invokeExact(data, width, height, bitsPerComponent, bytesPerRow,
                                                                  colorSpace, bitmapInfo);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static void cgContextSetBlendMode(MemorySegment context, int mode) {
    try {
      CG_CONTEXT_SET_BLEND_MODE.invokeExact(context, mode);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  /** {@code rect} is a {@link #CG_RECT} in context user space (origin bottom-left). */
  static void cgContextDrawImage(MemorySegment context, MemorySegment rect, MemorySegment image) {
    try {
      CG_CONTEXT_DRAW_IMAGE.invokeExact(context, rect, image);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static void setRect(MemorySegment rect, double x, double y, double width, double height) {
    rect.set(JAVA_DOUBLE, 0, x);
    rect.set(JAVA_DOUBLE, 8, y);
    rect.set(JAVA_DOUBLE, 16, width);
    rect.set(JAVA_DOUBLE, 24, height);
  }

  // ---------------------------------------------------------------------------------------------- libobjc
  static MemorySegment autoreleasePoolPush() {
    try {
      return (MemorySegment) OBJC_AUTORELEASE_POOL_PUSH.invokeExact();
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }

  static void autoreleasePoolPop(MemorySegment pool) {
    if (isNull(pool)) return;
    try {
      OBJC_AUTORELEASE_POOL_POP.invokeExact(pool);
    }
    catch (Throwable t) {
      throw propagate(t);
    }
  }
}
