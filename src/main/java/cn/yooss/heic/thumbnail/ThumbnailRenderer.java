package cn.yooss.heic.thumbnail;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;

/**
 * Renders a decoded image into square icon images (pure Java2D, no IDE dependencies).
 * <p>
 * The image is scaled to fit the square with its aspect ratio preserved and centered on a transparent background,
 * then outlined with a 1-device-pixel, semi-transparent mid-gray border that is visible but subtle on both light
 * and dark backgrounds. Downscaling halves the image repeatedly with bilinear filtering before the last step, which
 * averages all source pixels (a single bilinear/bicubic step from 64 to 16 pixels would alias).
 * <p>
 * For HiDPI screens {@link #renderIcon} returns a {@link BaseMultiResolutionImage}: Java2D picks the variant that
 * matches the device scale of the {@code Graphics} it is painted on, so the icon is drawn 1:1 in device pixels on a
 * Retina screen and on a regular screen. Only JDK classes are used for the result, so an icon made from it cannot
 * keep this plugin's class loader alive.
 */
final class ThumbnailRenderer {
  /** 50% gray at ~35% opacity. */
  static final Color BORDER_COLOR = new Color(128, 128, 128, 90);

  private ThumbnailRenderer() {
  }

  /**
   * Icon image of {@code iconSize x iconSize} user-space pixels with one variant per scale; {@code scales[0]} must
   * be 1 (the base image). Returns a plain {@link BufferedImage} when there is only one variant.
   */
  static Image renderIcon(BufferedImage source, int iconSize, double[] scales) {
    if (scales.length == 0 || scales[0] != 1.0) throw new IllegalArgumentException("scales must start with 1.0");
    BufferedImage base = render(source, iconSize, true);
    if (scales.length == 1) return base;
    Image[] variants = new Image[scales.length];
    variants[0] = base;
    int previous = iconSize;
    for (int i = 1; i < scales.length; i++) {
      int size = ThumbnailGeometry.variantSize(iconSize, scales[i]);
      if (size <= previous) throw new IllegalArgumentException("scales must be ascending");
      variants[i] = render(source, size, true);
      previous = size;
    }
    return new BaseMultiResolutionImage(variants);
  }

  /** Renders {@code source} into a transparent {@code box x box} image (aspect ratio preserved, centered). */
  static BufferedImage render(BufferedImage source, int box, boolean border) {
    Rectangle target = ThumbnailGeometry.fit(source.getWidth(), source.getHeight(), box);
    BufferedImage scaled = scale(source, target.width, target.height);
    BufferedImage result = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB_PRE);
    Graphics2D g = result.createGraphics();
    try {
      g.drawImage(scaled, target.x, target.y, null);
      if (border && target.width >= 3 && target.height >= 3) {
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(BORDER_COLOR);
        g.drawRect(target.x, target.y, target.width - 1, target.height - 1);
      }
    }
    finally {
      g.dispose();
    }
    return result;
  }

  /**
   * Scales {@code source} to exactly {@code width x height}: halving steps with bilinear filtering while the image
   * is at least twice as large as the target, then one bilinear step to the final size.
   */
  static BufferedImage scale(BufferedImage source, int width, int height) {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid size " + width + "x" + height);
    BufferedImage current = source;
    int w = source.getWidth();
    int h = source.getHeight();
    while (w >= 2 * width && h >= 2 * height) {
      w /= 2;
      h /= 2;
      current = draw(current, w, h);
    }
    if (w != width || h != height || current == source) {
      current = draw(current, width, height);
    }
    return current;
  }

  private static BufferedImage draw(BufferedImage source, int width, int height) {
    BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
    Graphics2D g = result.createGraphics();
    try {
      g.setComposite(AlphaComposite.Src);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
      g.drawImage(source, 0, 0, width, height, null);
    }
    finally {
      g.dispose();
    }
    return result;
  }
}
