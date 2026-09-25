package cn.yooss.heic.mac.jna;

import cn.yooss.heic.backend.jna.JnaLibraries;
import cn.yooss.heic.mac.MacApi;
import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.nio.charset.StandardCharsets;

/**
 * {@link MacApi} on top of the JNA that every IntelliJ-based IDE bundles (5.14 in 2024.1/2024.2 ... 5.17 in 2026.x).
 * <p>
 * <b>Unloadable by design</b> (see {@link JnaLibraries} for the rules): JNA keeps static caches keyed by the
 * {@code Class} objects handed to it ({@code Native.typeOptions}/{@code Native.libraries} for {@code Library}
 * interfaces, {@code Structure.layoutInfo}/{@code fieldOrder}/{@code fieldList} and {@code FFIType.typeInfoMap} for
 * {@code Structure}s, {@code CallbackReference} maps for callbacks). Their values point back at the key class, so an
 * entry never clears and pins the plugin class loader. This class therefore only uses JNA's untyped layer, which never
 * sees a plugin class:
 * <ul>
 *   <li>{@link NativeLibrary}s opened through {@link JnaLibraries#openAll} (no options, JNA's Cleaner thread guarded)
 *   and {@link NativeLibrary#getFunction(String)} / {@link Function#invokeLong(Object[])} etc. with arguments of JDK
 *   types only ({@code Long}, {@code Integer}, {@code Double}, {@code byte[]}, {@code long[]});</li>
 *   <li>no {@code Library} interface, no {@code Structure}, no {@code ByReference}, no callback;</li>
 *   <li>no {@code Memory}: native buffers come from {@link Native#malloc}/{@link Native#free} (a {@code Memory} would
 *   register with JNA's Cleaner, whose thread JNA 5.14+ may start lazily from the calling thread).</li>
 * </ul>
 * {@code CGRect} arguments (32-byte homogeneous aggregate of four doubles) are passed without a {@code Structure}:
 * <ul>
 *   <li>arm64 (AAPCS64/Apple): an HFA of four doubles is passed in four consecutive FP registers, exactly like four
 *   separate {@code double} arguments, so the four components are passed as doubles.</li>
 *   <li>x86_64 (System V): a struct larger than 16 bytes is class MEMORY and is copied to the stack argument area.
 *   Eight dummy doubles fill {@code xmm0}-{@code xmm7}; the four components that follow are then passed on the stack,
 *   in order, which is the same memory image as the by-value struct. Integer-class arguments (the references) are
 *   assigned to general-purpose registers independently of the doubles, so their positions do not matter.</li>
 * </ul>
 * Handles are passed as {@code Long} and returned with {@code invokeLong}: pointers and 64-bit integers use the same
 * registers on both ABIs. {@code Boolean} (unsigned char) results are read as int and masked to 8 bits.
 */
public final class JnaMacApi implements MacApi {
  private static final String FRAMEWORKS = "/System/Library/Frameworks/";

  // Opened without options (Native.load would add the interface's class loader to the options, and the NativeLibrary
  // keeps them) and through JnaLibraries (JNA's Cleaner thread must not be started with plugin state attached).
  private final NativeLibrary[] libraries = JnaLibraries.openAll(
      FRAMEWORKS + "CoreFoundation.framework/CoreFoundation", FRAMEWORKS + "ImageIO.framework/ImageIO",
      FRAMEWORKS + "CoreGraphics.framework/CoreGraphics", "/usr/lib/libobjc.A.dylib");
  private final NativeLibrary coreFoundation = libraries[0];
  private final NativeLibrary imageIO = libraries[1];
  private final NativeLibrary coreGraphics = libraries[2];
  private final NativeLibrary objc = libraries[3];

  private final RectPassing rectPassing = RectPassing.forCurrentPlatform();

  // CoreFoundation
  private final Function cfDataCreate = coreFoundation.getFunction("CFDataCreate");
  private final Function cfRelease = coreFoundation.getFunction("CFRelease");
  private final Function cfDictionaryCreateMutable = coreFoundation.getFunction("CFDictionaryCreateMutable");
  private final Function cfDictionarySetValue = coreFoundation.getFunction("CFDictionarySetValue");
  private final Function cfDictionaryGetValue = coreFoundation.getFunction("CFDictionaryGetValue");
  private final Function cfNumberCreate = coreFoundation.getFunction("CFNumberCreate");
  private final Function cfNumberGetValue = coreFoundation.getFunction("CFNumberGetValue");
  private final Function cfGetTypeID = coreFoundation.getFunction("CFGetTypeID");
  private final Function cfBooleanGetValue = coreFoundation.getFunction("CFBooleanGetValue");
  private final Function cfStringGetCString = coreFoundation.getFunction("CFStringGetCString");
  // ImageIO
  private final Function cgImageSourceCreateWithData = imageIO.getFunction("CGImageSourceCreateWithData");
  private final Function cgImageSourceGetCount = imageIO.getFunction("CGImageSourceGetCount");
  private final Function cgImageSourceGetPrimaryImageIndex = imageIO.getFunction("CGImageSourceGetPrimaryImageIndex");
  private final Function cgImageSourceGetStatus = imageIO.getFunction("CGImageSourceGetStatus");
  private final Function cgImageSourceGetType = imageIO.getFunction("CGImageSourceGetType");
  private final Function cgImageSourceCopyPropertiesAtIndex = imageIO.getFunction("CGImageSourceCopyPropertiesAtIndex");
  private final Function cgImageSourceCreateImageAtIndex = imageIO.getFunction("CGImageSourceCreateImageAtIndex");
  private final Function cgImageSourceCreateThumbnailAtIndex = imageIO.getFunction("CGImageSourceCreateThumbnailAtIndex");
  // CoreGraphics
  private final Function cgImageGetWidth = coreGraphics.getFunction("CGImageGetWidth");
  private final Function cgImageGetHeight = coreGraphics.getFunction("CGImageGetHeight");
  private final Function cgImageGetAlphaInfo = coreGraphics.getFunction("CGImageGetAlphaInfo");
  private final Function cgImageCreateWithImageInRect = coreGraphics.getFunction("CGImageCreateWithImageInRect");
  private final Function cgColorSpaceCreateWithName = coreGraphics.getFunction("CGColorSpaceCreateWithName");
  private final Function cgBitmapContextCreate = coreGraphics.getFunction("CGBitmapContextCreate");
  private final Function cgContextSetBlendMode = coreGraphics.getFunction("CGContextSetBlendMode");
  private final Function cgContextDrawImage = coreGraphics.getFunction("CGContextDrawImage");
  // libobjc
  private final Function autoreleasePoolPush = objc.getFunction("objc_autoreleasePoolPush");
  private final Function autoreleasePoolPop = objc.getFunction("objc_autoreleasePoolPop");

  private final long keyCallBacks = address(coreFoundation.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"));
  private final long valueCallBacks = address(coreFoundation.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"));
  private final long numberTypeId = coreFoundation.getFunction("CFNumberGetTypeID").invokeLong(new Object[0]);
  private final long booleanTypeId = coreFoundation.getFunction("CFBooleanGetTypeID").invokeLong(new Object[0]);

  private static final long kCFNumberSInt64Type = 4;
  private static final int kCFStringEncodingUTF8 = 0x08000100;

  public JnaMacApi() {
  }

  private static long address(Pointer pointer) {
    return Pointer.nativeValue(pointer);
  }

  @Override
  public String name() {
    return "JNA " + JnaLibraries.version() + " (" + rectPassing + ")";
  }

  @Override
  public long constant(Framework framework, String symbol) {
    NativeLibrary library;
    switch (framework) {
      case CORE_FOUNDATION: library = coreFoundation; break;
      case IMAGE_IO: library = imageIO; break;
      case CORE_GRAPHICS: library = coreGraphics; break;
      default: throw new IllegalArgumentException(String.valueOf(framework));
    }
    long value = address(library.getGlobalVariableAddress(symbol).getPointer(0));
    if (value == 0) throw new UnsatisfiedLinkError("Constant is NULL: " + symbol);
    return value;
  }

  // ---------------------------------------------------------------- CoreFoundation
  @Override
  public long cfDataCreate(byte[] bytes) {
    return cfDataCreate.invokeLong(new Object[]{0L, bytes, (long) bytes.length});
  }

  @Override
  public void cfRelease(long ref) {
    if (ref != 0) cfRelease.invokeVoid(new Object[]{ref});
  }

  @Override
  public long cfDictionaryCreateMutable(long capacity) {
    return cfDictionaryCreateMutable.invokeLong(new Object[]{0L, capacity, keyCallBacks, valueCallBacks});
  }

  @Override
  public void cfDictionarySetValue(long dictionary, long key, long value) {
    cfDictionarySetValue.invokeVoid(new Object[]{dictionary, key, value});
  }

  @Override
  public long cfDictionaryGetValue(long dictionary, long key) {
    return cfDictionaryGetValue.invokeLong(new Object[]{dictionary, key});
  }

  @Override
  public long cfNumberCreateSInt64(long value) {
    return cfNumberCreate.invokeLong(new Object[]{0L, kCFNumberSInt64Type, new long[]{value}});
  }

  @Override
  public long cfLongValue(long ref, long defaultValue) {
    if (ref == 0) return defaultValue;
    long type = cfGetTypeID.invokeLong(new Object[]{ref});
    if (type == booleanTypeId) return (cfBooleanGetValue.invokeInt(new Object[]{ref}) & 0xFF) != 0 ? 1 : 0;
    if (type != numberTypeId) return defaultValue;
    long[] cell = new long[1];
    // Returns false only for a lossy conversion (e.g. from a float); the truncated value is stored anyway.
    cfNumberGetValue.invokeInt(new Object[]{ref, kCFNumberSInt64Type, cell});
    return cell[0];
  }

  @Override
  public String cfString(long ref) {
    if (ref == 0) return null;
    byte[] buffer = new byte[512];
    int ok = cfStringGetCString.invokeInt(new Object[]{ref, buffer, (long) buffer.length, kCFStringEncodingUTF8}) & 0xFF;
    if (ok == 0) return null;
    int length = 0;
    while (length < buffer.length && buffer[length] != 0) length++;
    return new String(buffer, 0, length, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------- ImageIO
  @Override
  public long cgImageSourceCreateWithData(long data, long options) {
    return cgImageSourceCreateWithData.invokeLong(new Object[]{data, options});
  }

  @Override
  public long cgImageSourceGetCount(long source) {
    return cgImageSourceGetCount.invokeLong(new Object[]{source});
  }

  @Override
  public long cgImageSourceGetPrimaryImageIndex(long source) {
    return cgImageSourceGetPrimaryImageIndex.invokeLong(new Object[]{source});
  }

  @Override
  public int cgImageSourceGetStatus(long source) {
    return cgImageSourceGetStatus.invokeInt(new Object[]{source});
  }

  @Override
  public long cgImageSourceGetType(long source) {
    return cgImageSourceGetType.invokeLong(new Object[]{source});
  }

  @Override
  public long cgImageSourceCopyPropertiesAtIndex(long source, long index, long options) {
    return cgImageSourceCopyPropertiesAtIndex.invokeLong(new Object[]{source, index, options});
  }

  @Override
  public long cgImageSourceCreateImageAtIndex(long source, long index, long options) {
    return cgImageSourceCreateImageAtIndex.invokeLong(new Object[]{source, index, options});
  }

  @Override
  public long cgImageSourceCreateThumbnailAtIndex(long source, long index, long options) {
    return cgImageSourceCreateThumbnailAtIndex.invokeLong(new Object[]{source, index, options});
  }

  // ---------------------------------------------------------------- CoreGraphics
  @Override
  public long cgImageGetWidth(long image) {
    return cgImageGetWidth.invokeLong(new Object[]{image});
  }

  @Override
  public long cgImageGetHeight(long image) {
    return cgImageGetHeight.invokeLong(new Object[]{image});
  }

  @Override
  public int cgImageGetAlphaInfo(long image) {
    return cgImageGetAlphaInfo.invokeInt(new Object[]{image});
  }

  @Override
  public long cgImageCreateWithImageInRect(long image, double x, double y, double width, double height) {
    return cgImageCreateWithImageInRect.invokeLong(rectPassing.arguments(image, x, y, width, height, null));
  }

  @Override
  public long cgColorSpaceCreateWithName(long name) {
    return cgColorSpaceCreateWithName.invokeLong(new Object[]{name});
  }

  @Override
  public long cgBitmapContextCreate(long data, long width, long height, long bitsPerComponent, long bytesPerRow,
                                    long colorSpace, int bitmapInfo) {
    return cgBitmapContextCreate.invokeLong(new Object[]{data, width, height, bitsPerComponent, bytesPerRow, colorSpace, bitmapInfo});
  }

  @Override
  public void cgContextSetBlendMode(long context, int mode) {
    cgContextSetBlendMode.invokeVoid(new Object[]{context, mode});
  }

  @Override
  public void cgContextDrawImage(long context, double x, double y, double width, double height, long image) {
    cgContextDrawImage.invokeVoid(rectPassing.arguments(context, x, y, width, height, image));
  }

  // ---------------------------------------------------------------- libobjc
  @Override
  public long autoreleasePoolPush() {
    return autoreleasePoolPush.invokeLong(new Object[0]);
  }

  @Override
  public void autoreleasePoolPop(long pool) {
    if (pool != 0) autoreleasePoolPop.invokeVoid(new Object[]{pool});
  }

  // ---------------------------------------------------------------- native memory
  @Override
  public long malloc(long size) {
    return Native.malloc(size); // malloc on macOS is 16-byte aligned
  }

  @Override
  public void free(long address) {
    if (address != 0) Native.free(address);
  }

  @Override
  public void readInts(long address, int[] target, int count) {
    new Pointer(address).read(0, target, 0, count);
  }

  /**
   * How a by-value {@code CGRect} is spread over plain {@code double} arguments. Package-private for tests: the
   * x86_64 layout can be exercised on Apple silicon under Rosetta 2 with an x86_64 JVM.
   */
  enum RectPassing {
    /** arm64: the HFA goes to d0-d3, like four double arguments. */
    FOUR_DOUBLES(0),
    /** x86_64: xmm0-xmm7 are filled with dummies so that the four components land on the stack. */
    STACK_AFTER_EIGHT_DUMMIES(8);

    private final int padding;

    RectPassing(int padding) {
      this.padding = padding;
    }

    static RectPassing forCurrentPlatform() {
      if (!Platform.isMac()) throw new UnsatisfiedLinkError("macOS only");
      String arch = System.getProperty("os.arch", "");
      if (arch.equals("aarch64") || arch.equals("arm64")) return FOUR_DOUBLES;
      if (arch.equals("x86_64") || arch.equals("amd64")) return STACK_AFTER_EIGHT_DUMMIES;
      throw new UnsatisfiedLinkError("Unsupported architecture for CGRect passing: " + arch);
    }

    /** {@code first, [padding doubles], x, y, width, height[, last]}. */
    Object[] arguments(long first, double x, double y, double width, double height, Long last) {
      Object[] args = new Object[1 + padding + 4 + (last == null ? 0 : 1)];
      int i = 0;
      args[i++] = first;
      for (int p = 0; p < padding; p++) args[i++] = 0.0d;
      args[i++] = x;
      args[i++] = y;
      args[i++] = width;
      args[i++] = height;
      if (last != null) args[i] = last;
      return args;
    }

    @Override
    public String toString() {
      return this == FOUR_DOUBLES ? "arm64 HFA" : "x86_64 stack";
    }
  }
}
