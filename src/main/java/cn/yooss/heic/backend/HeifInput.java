package cn.yooss.heic.backend;

import cn.yooss.heic.HeifSniffer;

import java.io.IOException;

/**
 * Pure-Java gate in front of every system decoder: every {@link HeifBackend} decode method calls {@link #check} before
 * any native call. System decoders pick the codec by content (macOS ImageIO.framework, WIC) and decode a truncated file
 * as a black image.
 */
public final class HeifInput {
  /** Message of the {@link IOException} for data that is not HEIC/HEIF (asserted by tests). */
  public static final String NOT_HEIF = "Not a HEIC/HEIF file";

  private HeifInput() {
  }

  /**
   * Accepts only non-empty data that starts with a HEIC/HEIF {@code ftyp} box ({@link HeifSniffer}: no AVIF, no
   * MP4/MOV, no other image format) and is not cut short ({@link IsoBoxes}).
   *
   * @throws IOException with a message that explains the rejection
   */
  public static void check(byte[] data) throws IOException {
    if (data == null || data.length == 0) throw new IOException("Empty image data");
    if (!HeifSniffer.isHeif(data, data.length)) throw new IOException(NOT_HEIF);
    String truncation = IsoBoxes.findTruncation(data);
    if (truncation != null) throw new IOException("Truncated or corrupt HEIF file: " + truncation);
  }
}
