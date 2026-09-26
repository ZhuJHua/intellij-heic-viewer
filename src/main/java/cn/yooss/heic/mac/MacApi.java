package cn.yooss.heic.mac;

/**
 * The native primitives {@link HeicDecoder} needs from CoreFoundation, ImageIO.framework, CoreGraphics and the
 * Objective-C runtime, implemented by {@link cn.yooss.heic.mac.jna.JnaMacApi} (the IDE's bundled JNA).
 * <p>
 * Every {@code CF}/{@code CG} reference is passed as its raw address ({@code 0} = {@code NULL}). A {@code CGRect}
 * parameter is passed as its four components. Implementations must be thread-safe and must not keep per-call state.
 */
public interface MacApi {
  /** Short name for logs, e.g. {@code "JNA 5.17.0 (arm64 HFA)"}. */
  String name();

  /** Value of a global {@code CFStringRef}/{@code CFBooleanRef} constant (the pointer stored at the symbol). */
  long constant(Framework framework, String symbol);

  enum Framework { CORE_FOUNDATION, IMAGE_IO, CORE_GRAPHICS }

  // ---------------------------------------------------------------- CoreFoundation
  /** {@code CFDataCreate(NULL, bytes, bytes.length)}: CoreFoundation copies the bytes. */
  long cfDataCreate(byte[] bytes);

  /** {@code CFRelease}; a no-op for {@code 0} (CFRelease itself crashes on NULL). */
  void cfRelease(long ref);

  /** {@code CFDictionaryCreateMutable(NULL, capacity, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks)}. */
  long cfDictionaryCreateMutable(long capacity);

  void cfDictionarySetValue(long dictionary, long key, long value);

  /** "Get rule": the result is not owned by the caller. */
  long cfDictionaryGetValue(long dictionary, long key);

  /** {@code CFNumberCreate(NULL, kCFNumberSInt64Type, &value)}. */
  long cfNumberCreateSInt64(long value);

  /** Value of a {@code CFNumber} (as SInt64) or {@code CFBoolean} (0/1); {@code defaultValue} for NULL or other types. */
  long cfLongValue(long ref, long defaultValue);

  /** UTF-8 contents of a {@code CFString} of at most 511 bytes, {@code null} for NULL or on failure. */
  String cfString(long ref);

  // ---------------------------------------------------------------- ImageIO
  long cgImageSourceCreateWithData(long data, long options);

  long cgImageSourceGetCount(long source);

  long cgImageSourceGetPrimaryImageIndex(long source);

  int cgImageSourceGetStatus(long source);

  /** "Get rule": the returned UTI string is not owned by the caller. */
  long cgImageSourceGetType(long source);

  long cgImageSourceCopyPropertiesAtIndex(long source, long index, long options);

  long cgImageSourceCreateImageAtIndex(long source, long index, long options);

  long cgImageSourceCreateThumbnailAtIndex(long source, long index, long options);

  // ---------------------------------------------------------------- CoreGraphics
  long cgImageGetWidth(long image);

  long cgImageGetHeight(long image);

  int cgImageGetAlphaInfo(long image);

  /** {@code CGImageCreateWithImageInRect(image, CGRectMake(x, y, width, height))}, rect in pixels, origin top-left. */
  long cgImageCreateWithImageInRect(long image, double x, double y, double width, double height);

  long cgColorSpaceCreateWithName(long name);

  long cgBitmapContextCreate(long data, long width, long height, long bitsPerComponent, long bytesPerRow,
                             long colorSpace, int bitmapInfo);

  void cgContextSetBlendMode(long context, int mode);

  /** {@code CGContextDrawImage(context, CGRectMake(x, y, width, height), image)}, rect in user space (origin bottom-left). */
  void cgContextDrawImage(long context, double x, double y, double width, double height, long image);

  // ---------------------------------------------------------------- libobjc
  long autoreleasePoolPush();

  /** No-op for {@code 0}. */
  void autoreleasePoolPop(long pool);

  // ---------------------------------------------------------------- native memory
  /** {@code malloc(size)}, 16-byte aligned; {@code 0} if the allocation failed. */
  long malloc(long size);

  /** {@code free(address)}; a no-op for {@code 0}. */
  void free(long address);

  /** {@code memset(address, 0, size)}. */
  void zero(long address, long size);

  /** Copies {@code count} native-endian ints starting at {@code address} into {@code target[0..count)}. */
  void readInts(long address, int[] target, int count);
}
