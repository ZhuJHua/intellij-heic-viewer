package cn.yooss.heic.thumbnail;

import java.util.Objects;

/**
 * Identifies one rendered thumbnail icon: the file (URL), its version on disk (timestamp and length as recorded by
 * the VFS) and the target size (icon size in user-space pixels and the largest screen scale). A changed file, a
 * changed IDE scale or a new HiDPI screen therefore produce a new key instead of a stale icon.
 *
 * @param url          {@code VirtualFile.getUrl()}
 * @param timeStamp    {@code VirtualFile.getTimeStamp()}
 * @param length       {@code VirtualFile.getLength()}
 * @param iconSize     logical icon size in user-space pixels (16, scaled by the IDE's UI scale)
 * @param scalePercent largest screen scale in percent (100 = 1x, 200 = Retina)
 */
record ThumbnailKey(String url, long timeStamp, long length, int iconSize, int scalePercent) {
  ThumbnailKey {
    Objects.requireNonNull(url, "url");
    if (iconSize <= 0) throw new IllegalArgumentException("Invalid icon size " + iconSize);
    if (scalePercent < 100) throw new IllegalArgumentException("Invalid scale " + scalePercent + "%");
  }

  static ThumbnailKey of(String url, long timeStamp, long length, int iconSize, double maxScreenScale) {
    return new ThumbnailKey(url, timeStamp, length, iconSize, ThumbnailGeometry.scalePercent(maxScreenScale));
  }

  /** Scales to render variants for (1x first). */
  double[] scales() {
    return ThumbnailGeometry.variantScales(scalePercent);
  }

  /** {@code maxPixelSize} for the decoder: twice the largest variant, see {@link ThumbnailGeometry#decodeSize}. */
  int decodeSize() {
    return ThumbnailGeometry.decodeSize(ThumbnailGeometry.variantSize(iconSize, scalePercent / 100.0));
  }
}
