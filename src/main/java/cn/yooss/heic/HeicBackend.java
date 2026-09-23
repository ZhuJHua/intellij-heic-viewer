package cn.yooss.heic;

import cn.yooss.heic.mac.HeicDecoder;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * The native decoding layer used by {@link HeicImageReader}. The default implementation delegates to
 * {@link HeicDecoder} (macOS ImageIO.framework); tests substitute failing backends to prove that a broken native
 * layer cannot affect other image formats.
 */
interface HeicBackend {
  HeicDecoder.Info readInfo(byte[] data) throws IOException;

  /** @param maxPixelSize {@code 0} for full resolution, otherwise the maximum length of the longer side */
  BufferedImage decode(byte[] data, int maxPixelSize) throws IOException;

  /**
   * Delegates to {@link HeicDecoder}. Referencing this class does not load any native code: the FFM bindings are
   * initialized on the first actual {@code readInfo}/{@code decode} call.
   */
  HeicBackend MAC_IMAGE_IO = new HeicBackend() {
    @Override
    public HeicDecoder.Info readInfo(byte[] data) throws IOException {
      return HeicDecoder.readInfo(data);
    }

    @Override
    public BufferedImage decode(byte[] data, int maxPixelSize) throws IOException {
      return HeicDecoder.decode(data, maxPixelSize);
    }

    @Override
    public String toString() {
      return "macOS ImageIO.framework";
    }
  };
}
