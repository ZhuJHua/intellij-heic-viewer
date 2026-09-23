import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates bands.png (2000x1200): horizontal 100 px bands cycling through 6 colors, a black 60x60 marker in the
 * top-left corner. Large enough (2.4 MP) to be rendered in several strips by the decoder.
 * Encode with: sips -s format heic bands.png --out bands_2000x1200.heic
 */
public class GenBands {
  static final Color[] COLORS = {
      new Color(255, 0, 0), new Color(0, 255, 0), new Color(0, 0, 255),
      new Color(255, 255, 0), new Color(0, 255, 255), new Color(255, 0, 255)};

  public static void main(String[] args) throws Exception {
    BufferedImage image = new BufferedImage(2000, 1200, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    for (int band = 0; band < 12; band++) {
      g.setColor(COLORS[band % COLORS.length]);
      g.fillRect(0, band * 100, 2000, 100);
    }
    g.setColor(Color.BLACK);
    g.fillRect(0, 0, 60, 60);
    g.dispose();
    ImageIO.write(image, "png", new File(args.length > 0 ? args[0] : ".", "bands.png"));
  }
}
