package cn.yooss.heic.thumbnail;

import java.util.Objects;

/**
 * Identifies one rendered thumbnail icon: the file (URL), its version on disk (timestamp and length as recorded by
 * the VFS) and the target size (icon size in user-space pixels and the largest screen scale). A changed file, a
 * changed IDE scale or a new HiDPI screen therefore produce a new key instead of a stale icon.
 * <p>
 * Immutable, with hand-written {@code equals}/{@code hashCode}/{@code toString}: a record's are bootstrapped through
 * {@code java.lang.runtime.ObjectMethods}, which keeps the plugin class loader alive on Java 17 (IntelliJ 2024.1).
 */
final class ThumbnailKey {
  private final String url;
  private final long timeStamp;
  private final long length;
  private final int iconSize;
  private final int scalePercent;

  /**
   * @param url          {@code VirtualFile.getUrl()}
   * @param timeStamp    {@code VirtualFile.getTimeStamp()}
   * @param length       {@code VirtualFile.getLength()}
   * @param iconSize     logical icon size in user-space pixels (16, scaled by the IDE's UI scale)
   * @param scalePercent largest screen scale in percent (100 = 1x, 200 = Retina)
   */
  ThumbnailKey(String url, long timeStamp, long length, int iconSize, int scalePercent) {
    this.url = Objects.requireNonNull(url, "url");
    if (iconSize <= 0) throw new IllegalArgumentException("Invalid icon size " + iconSize);
    if (scalePercent < 100) throw new IllegalArgumentException("Invalid scale " + scalePercent + "%");
    this.timeStamp = timeStamp;
    this.length = length;
    this.iconSize = iconSize;
    this.scalePercent = scalePercent;
  }

  static ThumbnailKey of(String url, long timeStamp, long length, int iconSize, double maxScreenScale) {
    return new ThumbnailKey(url, timeStamp, length, iconSize, ThumbnailGeometry.scalePercent(maxScreenScale));
  }

  String url() {
    return url;
  }

  long timeStamp() {
    return timeStamp;
  }

  long length() {
    return length;
  }

  int iconSize() {
    return iconSize;
  }

  int scalePercent() {
    return scalePercent;
  }

  /** Scales to render variants for (1x first). */
  double[] scales() {
    return ThumbnailGeometry.variantScales(scalePercent);
  }

  /** {@code maxPixelSize} for the decoder: twice the largest variant, see {@link ThumbnailGeometry#decodeSize}. */
  int decodeSize() {
    return ThumbnailGeometry.decodeSize(ThumbnailGeometry.variantSize(iconSize, scalePercent / 100.0));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ThumbnailKey)) return false;
    ThumbnailKey other = (ThumbnailKey) o;
    return timeStamp == other.timeStamp && length == other.length && iconSize == other.iconSize
           && scalePercent == other.scalePercent && url.equals(other.url);
  }

  @Override
  public int hashCode() {
    int result = url.hashCode();
    result = 31 * result + Long.hashCode(timeStamp);
    result = 31 * result + Long.hashCode(length);
    result = 31 * result + iconSize;
    result = 31 * result + scalePercent;
    return result;
  }

  @Override
  public String toString() {
    return "ThumbnailKey[url=" + url + ", timeStamp=" + timeStamp + ", length=" + length + ", iconSize=" + iconSize
           + ", scalePercent=" + scalePercent + "]";
  }
}
