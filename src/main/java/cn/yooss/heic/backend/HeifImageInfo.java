package cn.yooss.heic.backend;

import java.util.Objects;

/**
 * Cheap metadata of the primary image of a HEIF file, as reported by {@link HeifBackend#readInfo} (no pixel decoding).
 * <p>
 * The <em>display</em> size ({@link #width()} x {@link #height()}) is the size of the image returned by
 * {@link HeifBackend#decode decode(data, 0)}: the stored size with the orientation applied. A backend whose decoder
 * already applies the HEIF transformations ({@code irot}/{@code imir}, e.g. libheif) reports the transformed size as
 * the raw size and orientation {@code 1}.
 * <p>
 * Immutable, with hand-written {@code equals}/{@code hashCode}/{@code toString} (see {@link HeifBackendStatus}).
 */
public final class HeifImageInfo {
  private final String typeIdentifier;
  private final int imageCount;
  private final int primaryIndex;
  private final int rawWidth;
  private final int rawHeight;
  private final int orientation;
  private final int bitDepth;
  private final boolean hasAlpha;

  /**
   * @param typeIdentifier backend-specific format identifier: the UTI on macOS ({@code public.heic},
   *                       {@code public.heics}, ...), a short name such as {@code heic} elsewhere; may be {@code null}
   * @param imageCount     number of top-level images (at least 1)
   * @param primaryIndex   index of the primary image
   * @param rawWidth       width as stored, before orientation
   * @param rawHeight      height as stored, before orientation
   * @param orientation    EXIF-style orientation 1..8 still to be applied to the stored image (HEIF
   *                       {@code irot}/{@code imir} are reported the same way); values outside 1..8 count as 1
   * @param bitDepth       bits per component as stored, or -1 if unknown
   * @param hasAlpha       whether the image has an alpha channel (then decoded as {@code TYPE_INT_ARGB})
   */
  public HeifImageInfo(String typeIdentifier, int imageCount, int primaryIndex, int rawWidth, int rawHeight,
                       int orientation, int bitDepth, boolean hasAlpha) {
    this.typeIdentifier = typeIdentifier;
    this.imageCount = imageCount;
    this.primaryIndex = primaryIndex;
    this.rawWidth = rawWidth;
    this.rawHeight = rawHeight;
    this.orientation = orientation >= 1 && orientation <= 8 ? orientation : 1;
    this.bitDepth = bitDepth;
    this.hasAlpha = hasAlpha;
  }

  public String typeIdentifier() {
    return typeIdentifier;
  }

  public int imageCount() {
    return imageCount;
  }

  public int primaryIndex() {
    return primaryIndex;
  }

  public int rawWidth() {
    return rawWidth;
  }

  public int rawHeight() {
    return rawHeight;
  }

  public int orientation() {
    return orientation;
  }

  public int bitDepth() {
    return bitDepth;
  }

  public boolean hasAlpha() {
    return hasAlpha;
  }

  /** Orientations 5..8 rotate by 90 degrees, so the displayed image has width and height swapped. */
  public boolean swapsAxes() {
    return swapsAxes(orientation);
  }

  /** Whether EXIF orientation {@code orientation} (1..8) swaps width and height. */
  public static boolean swapsAxes(int orientation) {
    return orientation >= 5 && orientation <= 8;
  }

  /** Display width (orientation applied). */
  public int width() {
    return swapsAxes() ? rawHeight : rawWidth;
  }

  /** Display height (orientation applied). */
  public int height() {
    return swapsAxes() ? rawWidth : rawHeight;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof HeifImageInfo)) return false;
    HeifImageInfo other = (HeifImageInfo) o;
    return imageCount == other.imageCount && primaryIndex == other.primaryIndex && rawWidth == other.rawWidth
           && rawHeight == other.rawHeight && orientation == other.orientation && bitDepth == other.bitDepth
           && hasAlpha == other.hasAlpha && Objects.equals(typeIdentifier, other.typeIdentifier);
  }

  @Override
  public int hashCode() {
    int result = Objects.hashCode(typeIdentifier);
    result = 31 * result + imageCount;
    result = 31 * result + primaryIndex;
    result = 31 * result + rawWidth;
    result = 31 * result + rawHeight;
    result = 31 * result + orientation;
    result = 31 * result + bitDepth;
    result = 31 * result + (hasAlpha ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return "HeifImageInfo[type=" + typeIdentifier + ", images=" + imageCount + ", primary=" + primaryIndex + ", raw="
           + rawWidth + "x" + rawHeight + ", orientation=" + orientation + ", bitDepth=" + bitDepth + ", alpha="
           + hasAlpha + "]";
  }
}
