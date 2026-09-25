package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackends;

/** JUnit conditions on the system decoder of the OS the tests run on. */
public final class SystemDecoder {
  private SystemDecoder() {
  }

  /** Whether {@code HeifBackends.current()} can decode (macOS; Windows/Linux with the system codecs installed). */
  public static boolean isAvailable() {
    return HeifBackends.current().status().isAvailable();
  }

  /**
   * The tolerance of a color comparison with the fixtures' source PNGs: {@code usual}, or {@code windows} for the Windows
   * backend. Microsoft's HEIF decoder (HEIF Image Extension 1.2.36, checked in CI on Windows 11 arm64) converts several
   * single-image fixtures that signal BT.601 YCbCr coefficients as if they were BT.709 (pure red decodes as
   * (255, 25, 0); mean differences up to about 9.5 instead of below 2.5), while grid images and the libheif 10-bit
   * fixture come out right. Sizes, orientation and alpha are not affected. The plugin shows what Windows decodes (README,
   * "Limitations and known issues"), so these comparisons are wider on Windows.
   */
  public static double colorTolerance(double usual, double windows) {
    return HeifBackends.current().id().equals("windows-wic") ? windows : usual;
  }
}
