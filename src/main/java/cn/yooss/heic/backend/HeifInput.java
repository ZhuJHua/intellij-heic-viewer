package cn.yooss.heic.backend;

import cn.yooss.heic.HeifSniffer;

import java.io.IOException;

/**
 * Pure-Java gate in front of every system decoder. System codecs pick the codec by content (macOS ImageIO.framework,
 * WIC) or "successfully" decode truncated files as black images, so every {@link HeifBackend} decode method calls
 * {@link #check} before any native call, whoever the caller is (the image reader, however it was looked up, or the
 * thumbnail icons).
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

  /** Description of the problem if {@code data} is an ISO-BMFF file that is cut short, else {@code null}. */
  public static String findTruncation(byte[] data) {
    return IsoBoxes.findTruncation(data);
  }
}
