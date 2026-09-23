package cn.yooss.heic;

/**
 * Pixel budget for decoding. The IDE always asks the reader for full resolution (and the diff viewer decodes two
 * images at once), so images above the budget are downscaled while decoding, preserving the aspect ratio.
 *
 * @param maxPixels maximum number of pixels of the decoded image
 * @param maxSide   maximum length of the longer side of the decoded image
 */
public record DecodeLimits(long maxPixels, int maxSide) {
  public static final int DEFAULT_MAX_MEGAPIXELS = 64;
  public static final int MIN_MEGAPIXELS = 1;
  /** Upper bound of the setting: 512 MP already needs 2 GB of Java heap for a single decoded image. */
  public static final int MAX_MEGAPIXELS = 512;
  public static final int DEFAULT_MAX_SIDE = 16384;
  public static final DecodeLimits DEFAULT = ofMegapixels(DEFAULT_MAX_MEGAPIXELS);

  public DecodeLimits {
    if (maxPixels < 1) throw new IllegalArgumentException("maxPixels must be >= 1: " + maxPixels);
    if (maxSide < 1) throw new IllegalArgumentException("maxSide must be >= 1: " + maxSide);
  }

  /** Budget of {@code megapixels} million pixels (clamped to [1, 512]) and a longest side of 16384. */
  public static DecodeLimits ofMegapixels(int megapixels) {
    int clamped = Math.max(MIN_MEGAPIXELS, Math.min(MAX_MEGAPIXELS, megapixels));
    return new DecodeLimits(clamped * 1_000_000L, DEFAULT_MAX_SIDE);
  }

  /**
   * Returns the longest side to decode an image of {@code width x height} display pixels with (the value for
   * {@code kCGImageSourceThumbnailMaxPixelSize}), or {@code 0} if the full image fits into the budget.
   */
  public int maxPixelSizeFor(int width, int height) {
    if (width <= 0 || height <= 0) return 0;
    long longest = Math.max(width, height);
    double pixels = (double) width * height;
    double scale = Math.min(1.0, Math.min(Math.sqrt(maxPixels / pixels), (double) maxSide / longest));
    if (scale >= 1.0) return 0;
    long side = (long) Math.floor(longest * scale);
    return (int) Math.max(1, Math.min(side, longest));
  }
}
