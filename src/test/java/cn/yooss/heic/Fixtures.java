package cn.yooss.heic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;

/**
 * Test fixtures in {@code src/test/resources/fixtures} (all synthetic, see {@code src/test/fixture-generators}).
 * <p>
 * The quadrant fixture is 600x400: TL red, TR green, BL blue, BR white, a black 40x40 marker in the top-left corner
 * and a brown patch in the centre. The alpha fixture is 400x300 with four 100 px columns: transparent, red at
 * alpha 128, blue at alpha 255, green at alpha 64. The 10-bit fixtures are a 512x256 gradient.
 */
public final class Fixtures {
  /** HEIF files that decode successfully. */
  public static final String[] DECODABLE = {
      "rgb_sips.heic", "rgb_libheif.heic", "alpha_sips.heic", "alpha_libheif.heic", "rgb16_sips.heic", "ten_bit.heic",
      "exif3_apple.heic", "exif5_apple.heic", "exif6_apple.heic", "rot90_irot.heic", "fliph_imir.heic",
      "grid_libheif.heic", "multi.heic", "seq.heics"};

  private Fixtures() {
  }

  public static byte[] bytes(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) throw new IllegalArgumentException("Missing fixture " + name);
      return in.readAllBytes();
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Reads a PNG reference with the JDK reader. */
  public static BufferedImage png(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) throw new IllegalArgumentException("Missing fixture " + name);
      return ImageIO.read(in);
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Coarse color name of a pixel (straight alpha). */
  public static String colorName(int argb) {
    int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
    if (a < 20) return "transparent";
    String c = r > 200 && g < 60 && b < 60 ? "red"
             : g > 200 && r < 60 && b < 60 ? "green"
             : b > 200 && r < 60 && g < 60 ? "blue"
             : r > 200 && g > 200 && b > 200 ? "white"
             : r < 40 && g < 40 && b < 40 ? "black"
             : String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    return a < 235 ? c + "@" + a : c;
  }

  /**
   * Describes the quadrant fixture as displayed: {@code "WxH TL=.. TR=.. BL=.. BR=.. marker=.."}, where marker is
   * the corner that holds the black square.
   */
  public static String layout(BufferedImage image) {
    int w = image.getWidth(), h = image.getHeight();
    String marker = "none";
    int[][] corners = {{8, 8}, {w - 9, 8}, {8, h - 9}, {w - 9, h - 9}};
    String[] cornerNames = {"TL", "TR", "BL", "BR"};
    for (int i = 0; i < 4; i++) {
      if (colorName(image.getRGB(corners[i][0], corners[i][1])).equals("black")) marker = cornerNames[i];
    }
    return w + "x" + h
           + " TL=" + colorName(image.getRGB(w / 4, h / 4))
           + " TR=" + colorName(image.getRGB(3 * w / 4, h / 4))
           + " BL=" + colorName(image.getRGB(w / 4, 3 * h / 4))
           + " BR=" + colorName(image.getRGB(3 * w / 4, 3 * h / 4))
           + " marker=" + marker;
  }

  /** Mean absolute per-channel (RGB) difference of {@code getRGB} values; pixels transparent in {@code b} are skipped. */
  public static double meanDifference(BufferedImage a, BufferedImage b) {
    if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
      throw new AssertionError("Size mismatch: " + a.getWidth() + "x" + a.getHeight() + " vs " + b.getWidth() + "x" + b.getHeight());
    }
    long sum = 0, n = 0;
    for (int y = 0; y < a.getHeight(); y++) {
      for (int x = 0; x < a.getWidth(); x++) {
        int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
        if ((pb >>> 24) == 0) continue;
        for (int shift = 0; shift < 24; shift += 8) {
          sum += Math.abs(((pa >> shift) & 255) - ((pb >> shift) & 255));
          n++;
        }
      }
    }
    return n == 0 ? 0 : (double) sum / n;
  }

  /**
   * {@code data} with its {@code ispe} box of {@code width x height} changed to {@code newWidth x newHeight}: a file that
   * declares another size than its coded image (a malformed file; libheif's decode limit).
   */
  public static byte[] withIspe(byte[] data, int width, int height, int newWidth, int newHeight) {
    byte[] copy = data.clone();
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(copy); // big-endian
    for (int i = 4; i + 16 <= copy.length; i++) {
      if (copy[i] == 'i' && copy[i + 1] == 's' && copy[i + 2] == 'p' && copy[i + 3] == 'e' && buffer.getInt(i - 4) == 20
          && buffer.getInt(i + 8) == width && buffer.getInt(i + 12) == height) {
        buffer.putInt(i + 8, newWidth);
        buffer.putInt(i + 12, newHeight);
        return copy;
      }
    }
    throw new IllegalArgumentException("no ispe " + width + "x" + height);
  }

  public static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
  }
}
