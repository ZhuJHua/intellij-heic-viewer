package cn.yooss.heic.thumbnail;

import java.awt.AWTError;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;

/** Sizes of thumbnail icons (pure arithmetic, no IDE dependencies). */
final class ThumbnailGeometry {
  /** Smallest and largest {@code maxPixelSize} requested from the decoder. */
  static final int MIN_DECODE_SIZE = 32;
  static final int MAX_DECODE_SIZE = 256;
  /** Screen scales above this are treated as this (keeps the decoded thumbnail small). */
  static final double MAX_SCALE = 4.0;

  private ThumbnailGeometry() {
  }

  /**
   * The largest rectangle with the aspect ratio of a {@code width x height} image that fits into a
   * {@code box x box} square, centered in it. Both sides are at least 1 pixel. The image may be scaled up or down.
   */
  static Rectangle fit(int width, int height, int box) {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image size " + width + "x" + height);
    if (box <= 0) throw new IllegalArgumentException("Invalid box size " + box);
    int w, h;
    if (width >= height) {
      w = box;
      h = (int) Math.max(1, Math.min(box, Math.round((double) height * box / width)));
    }
    else {
      h = box;
      w = (int) Math.max(1, Math.min(box, Math.round((double) width * box / height)));
    }
    return new Rectangle((box - w) / 2, (box - h) / 2, w, h);
  }

  /** Size in device pixels of an icon of {@code iconSize} user-space pixels painted at {@code scale}. */
  static int variantSize(int iconSize, double scale) {
    if (iconSize <= 0) throw new IllegalArgumentException("Invalid icon size " + iconSize);
    return (int) Math.max(1, Math.round(iconSize * normalizeScale(scale)));
  }

  /**
   * The {@code maxPixelSize} to decode for an icon whose largest variant is {@code largestVariant} device pixels:
   * twice that (the extra resolution is averaged away while downscaling), clamped to
   * [{@value #MIN_DECODE_SIZE}, {@value #MAX_DECODE_SIZE}].
   */
  static int decodeSize(int largestVariant) {
    return Math.max(MIN_DECODE_SIZE, Math.min(MAX_DECODE_SIZE, 2 * largestVariant));
  }

  /** Clamps a screen scale to [1, {@value #MAX_SCALE}]; NaN and infinities count as 1. */
  static double normalizeScale(double scale) {
    if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 1) return 1;
    return Math.min(scale, MAX_SCALE);
  }

  /** Scale in percent (100 = 1x), the form in which it is part of a cache key. */
  static int scalePercent(double scale) {
    return (int) Math.round(normalizeScale(scale) * 100);
  }

  /** The scales to render icon variants for: 1x (the base image) and, on HiDPI screens, the largest screen scale. */
  static double[] variantScales(int scalePercent) {
    return scalePercent <= 100 ? new double[]{1.0} : new double[]{1.0, scalePercent / 100.0};
  }

  /**
   * Largest device scale of all screens (2.0 on a Retina display, 1.0 when headless). Using the largest one makes
   * the icon crisp on every screen of a mixed setup; smaller screens use the 1x variant.
   */
  static double maxScreenScale() {
    try {
      if (GraphicsEnvironment.isHeadless()) return 1;
      double max = 1;
      for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
        AffineTransform transform = device.getDefaultConfiguration().getDefaultTransform();
        max = Math.max(max, Math.max(transform.getScaleX(), transform.getScaleY()));
      }
      return normalizeScale(max);
    }
    catch (RuntimeException | AWTError e) {
      return 1;
    }
  }
}
