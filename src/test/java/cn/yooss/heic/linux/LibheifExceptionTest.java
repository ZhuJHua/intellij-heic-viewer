package cn.yooss.heic.linux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Decoding and naming {@code struct heif_error} values (pure Java, runs on every OS; see LibheifAbiTest for the ABI). */
class LibheifExceptionTest {
  @Test
  void splitsTheFirstEightBytes() {
    long raw = (long) 2000 << 32 | 5;
    assertEquals(5, LibheifException.code(raw));
    assertEquals(2000, LibheifException.subcode(raw));
    assertEquals(-1, LibheifException.code(0xFFFF_FFFFL));
    assertEquals(0, LibheifException.subcode(0xFFFF_FFFFL));
  }

  @Test
  void checkThrowsForEveryCodeButOk() {
    assertDoesNotThrow(() -> LibheifException.check(0, "heif_init"));
    // heif_error_Ok with a garbage subcode is still Ok (only code decides)
    assertDoesNotThrow(() -> LibheifException.check((long) 7 << 32, "heif_init"));
    LibheifException e = assertThrows(LibheifException.class, () -> LibheifException.check((long) 102 << 32 | 2, "heif_x"));
    assertEquals(2, e.code());
    assertEquals(102, e.subcode());
    assertEquals("heif_x failed: Invalid input: No 'ftyp' box (heif_error 2/102)", e.getMessage());
  }

  @Test
  void names() {
    assertEquals("Unsupported feature: Unsupported codec (no decoder for this compression format) (heif_error 4/3000)",
                 LibheifException.describe(4, 3000));
    assertEquals("Decoder plugin error (heif_error 7/0)", LibheifException.describe(7, 0));
    assertEquals("Error 99: Suberror 77 (heif_error 99/77)", LibheifException.describe(99, 77));
    assertEquals("Memory allocation error: Security limit exceeded (heif_error 6/1000)", LibheifException.describe(6, 1000));
  }
}
