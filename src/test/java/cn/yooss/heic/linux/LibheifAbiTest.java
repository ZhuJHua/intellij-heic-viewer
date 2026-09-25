package cn.yooss.heic.linux;

import cn.yooss.heic.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code struct heif_error} return convention of {@link Libheif}: a 16-byte struct returned by value is read as the
 * 64-bit integer in the first return register ({@code RAX} on x86-64, {@code X0} on AArch64), {@code code} in the low
 * and {@code subcode} in the high half. Real libheif errors with known codes prove it on the CPU the tests run on (CI:
 * Linux x86-64 and AArch64; locally also macOS with Homebrew's libheif).
 */
@EnabledIf("cn.yooss.heic.linux.TestLibheif#isLoadable")
class LibheifAbiTest {
  private static Libheif lib() {
    Libheif lib = TestLibheif.backend().library();
    assertNotNull(lib);
    return lib;
  }

  @Test
  void successIsZero() throws LibheifException {
    lib().init(); // heif_error_Ok = {0, 0, "Success"}; one more reference, never released (like the backend)
  }

  /** Data that is not HEIF: heif_error_Invalid_input (2) with a nonzero subcode in the 100s (parse errors). */
  @Test
  void invalidInputHasCodeAndSubcode() throws IOException {
    Libheif lib = lib();
    byte[] data = new byte[64];
    for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 37 + 11);
    long memory = Libheif.copyToNative(data);
    long context = lib.contextAlloc();
    try {
      LibheifException e = assertThrows(LibheifException.class,
                                        () -> lib.readFromMemoryWithoutCopy(context, memory, data.length));
      System.out.println("garbage: " + e.getMessage());
      assertTrue(e.code() == 2 || e.code() == 3, e.getMessage()); // Invalid_input or Unsupported_filetype
      assertNotEquals(0, e.subcode(), e.getMessage());
      assertTrue(e.subcode() >= 100 && e.subcode() < 200, e.getMessage());
    }
    finally {
      lib.contextFree(context);
      Libheif.free(memory);
    }
  }

  /** A thumbnail id that does not exist: an error whose subcode is far above the 16-bit range of a code. */
  @Test
  void missingItemHasCodeAndSubcode() throws IOException {
    Libheif lib = lib();
    byte[] data = Fixtures.bytes("rgb_libheif.heic");
    long memory = Libheif.copyToNative(data);
    long context = lib.contextAlloc();
    long handle = 0;
    try {
      lib.readFromMemoryWithoutCopy(context, memory, data.length);
      handle = lib.primaryImageHandle(context);
      long primary = handle;
      LibheifException e = assertThrows(LibheifException.class, () -> lib.thumbnail(primary, 12345));
      System.out.println("missing item: " + e.getMessage());
      assertTrue(e.code() == 2 || e.code() == 5, e.getMessage()); // Invalid_input or Usage_error
      assertEquals(2000, e.subcode(), e.getMessage()); // Nonexisting_item_referenced
    }
    finally {
      lib.release(handle);
      lib.contextFree(context);
      Libheif.free(memory);
    }
  }
}
