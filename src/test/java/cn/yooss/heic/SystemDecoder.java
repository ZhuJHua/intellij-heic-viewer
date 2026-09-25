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
}
