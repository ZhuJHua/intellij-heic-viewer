package cn.yooss.heic.mac.jna;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a by-value {@code CGRect} is spread over JNA arguments. Pure Java; that the layouts match the real calling
 * conventions is shown by HeicDecoderTest's cropped-strip rendering on arm64 and x86_64 Macs.
 */
class RectPassingTest {
  @Test
  void arm64PassesTheFourComponentsLikeDoubleArguments() {
    assertArrayEquals(new Object[]{7L, 1.0, 2.5, 42.0, 7.0, 9L},
                      JnaMacApi.RectPassing.FOUR_DOUBLES.arguments(7L, 1.0, 2.5, 42.0, 7.0, 9L));
    assertArrayEquals(new Object[]{7L, 1.0, 2.5, 42.0, 7.0},
                      JnaMacApi.RectPassing.FOUR_DOUBLES.arguments(7L, 1.0, 2.5, 42.0, 7.0, null));
  }

  @Test
  void x86_64FillsTheEightSseRegistersFirst() {
    Object[] arguments = JnaMacApi.RectPassing.STACK_AFTER_EIGHT_DUMMIES.arguments(7L, 1.0, 2.5, 42.0, 7.0, 9L);
    assertEquals(14, arguments.length);
    assertEquals(7L, arguments[0]);
    for (int i = 1; i <= 8; i++) assertEquals(0.0, arguments[i]);
    assertArrayEquals(new Object[]{1.0, 2.5, 42.0, 7.0, 9L}, java.util.Arrays.copyOfRange(arguments, 9, 14));
  }
}
