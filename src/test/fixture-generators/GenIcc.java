import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Generates the sources of icc_wide.heic, a HEIC whose colors are in a wide-gamut RGB space described by an embedded ICC
 * profile (colr box of type prof), like iPhone photos in Display P3:
 *   wide.icc  an ICC v2 matrix/TRC display profile written from scratch here: Display P3 primaries (D65 white,
 *             Bradford-adapted to the D50 PCS) and a plain gamma 2.2 curve
 *   icc.png   400x300 (no profile), four 200x150 patches: TL (200, 60, 60), TR (60, 180, 80), BL (70, 90, 200),
 *             BR (128, 128, 128); values in the wide space
 * Then (macOS 26, Homebrew libheif 1.23.5):
 *   sips --embedProfile wide.icc icc.png --out icc_wide.png
 *   heif-enc -q 95 -o icc_wide.heic icc_wide.png
 * Decoded to sRGB, the colored patches become visibly more saturated; the gray one stays about the same.
 * Run: java GenIcc.java <outputDir>
 */
public class GenIcc {
  // Display P3 primaries and D65 white (x, y)
  static final double[][] PRIMARIES = {{0.680, 0.320}, {0.265, 0.690}, {0.150, 0.060}};
  static final double[] WHITE = {0.3127, 0.3290};
  static final double[] D50 = {0.9642, 1.0, 0.8249};

  public static void main(String[] args) throws Exception {
    File dir = new File(args.length > 0 ? args[0] : ".");
    Files.write(new File(dir, "wide.icc").toPath(), profile());
    BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(new Color(200, 60, 60)); g.fillRect(0, 0, 200, 150);
    g.setColor(new Color(60, 180, 80)); g.fillRect(200, 0, 200, 150);
    g.setColor(new Color(70, 90, 200)); g.fillRect(0, 150, 200, 150);
    g.setColor(new Color(128, 128, 128)); g.fillRect(200, 150, 200, 150);
    g.dispose();
    ImageIO.write(image, "png", new File(dir, "icc.png"));
  }

  static byte[] profile() throws IOException {
    double[][] colorants = adaptToD50(rgbToXyz());
    byte[][] tags = {
        textDescription("Wide gamut test profile (P3 primaries, gamma 2.2)"),
        text("No copyright, use freely"),
        xyz(D50[0], D50[1], D50[2]),
        xyz(colorants[0][0], colorants[1][0], colorants[2][0]),
        xyz(colorants[0][1], colorants[1][1], colorants[2][1]),
        xyz(colorants[0][2], colorants[1][2], colorants[2][2]),
        gamma(2.2),
    };
    String[] signatures = {"desc", "cprt", "wtpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC"};
    int[] data = {0, 1, 2, 3, 4, 5, 6, 6, 6}; // the three TRCs share one curve
    int offset = 128 + 4 + 12 * signatures.length;
    int[] offsets = new int[tags.length];
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (int i = 0; i < tags.length; i++) {
      offsets[i] = offset + body.size();
      body.write(tags[i]);
      while (body.size() % 4 != 0) body.write(0);
    }
    int size = offset + body.size();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeInt(size);
    d.writeInt(0);                       // preferred CMM
    d.writeInt(0x02100000);              // version 2.1
    d.writeBytes("mntr");
    d.writeBytes("RGB ");
    d.writeBytes("XYZ ");
    for (int v : new int[]{2026, 9, 25, 0, 0, 0}) d.writeShort(v);
    d.writeBytes("acsp");
    d.writeInt(0);                       // platform
    d.writeInt(0);                       // flags
    d.writeInt(0);                       // manufacturer
    d.writeInt(0);                       // model
    d.writeLong(0);                      // attributes
    d.writeInt(0);                       // perceptual intent
    d.writeInt(s15(D50[0])); d.writeInt(s15(D50[1])); d.writeInt(s15(D50[2]));
    d.writeInt(0);                       // creator
    d.write(new byte[44]);               // reserved
    d.writeInt(signatures.length);
    for (int i = 0; i < signatures.length; i++) {
      d.writeBytes(signatures[i]);
      d.writeInt(offsets[data[i]]);
      d.writeInt(tags[data[i]].length);
    }
    d.write(body.toByteArray());
    return out.toByteArray();
  }

  /** Columns: XYZ of the red, green and blue primaries (Y of white = 1). */
  static double[][] rgbToXyz() {
    double[][] m = new double[3][3];
    for (int c = 0; c < 3; c++) {
      double x = PRIMARIES[c][0], y = PRIMARIES[c][1];
      m[0][c] = x / y; m[1][c] = 1; m[2][c] = (1 - x - y) / y;
    }
    double[] white = {WHITE[0] / WHITE[1], 1, (1 - WHITE[0] - WHITE[1]) / WHITE[1]};
    double[] s = solve(m, white);
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r][c] *= s[c];
    return m;
  }

  /** Bradford adaptation of the matrix from D65 to D50; result rows: X, Y, Z; columns: R, G, B. */
  static double[][] adaptToD50(double[][] m) {
    double[][] bradford = {{0.8951, 0.2664, -0.1614}, {-0.7502, 1.7135, 0.0367}, {0.0389, -0.0685, 1.0296}};
    double[] src = multiply(bradford, new double[]{WHITE[0] / WHITE[1], 1, (1 - WHITE[0] - WHITE[1]) / WHITE[1]});
    double[] dst = multiply(bradford, D50);
    double[][] scale = {{dst[0] / src[0], 0, 0}, {0, dst[1] / src[1], 0}, {0, 0, dst[2] / src[2]}};
    double[][] adapt = multiply(inverse(bradford), multiply(scale, bradford));
    return multiply(adapt, m);
  }

  static byte[] textDescription(String value) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeBytes("desc");
    d.writeInt(0);
    byte[] ascii = (value + "\0").getBytes(StandardCharsets.US_ASCII);
    d.writeInt(ascii.length);
    d.write(ascii);
    d.writeInt(0);        // Unicode language
    d.writeInt(0);        // Unicode count
    d.writeShort(0);      // ScriptCode code
    d.writeByte(0);       // ScriptCode count
    d.write(new byte[67]);
    return out.toByteArray();
  }

  static byte[] text(String value) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeBytes("text");
    d.writeInt(0);
    d.write((value + "\0").getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  static byte[] xyz(double x, double y, double z) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeBytes("XYZ ");
    d.writeInt(0);
    d.writeInt(s15(x)); d.writeInt(s15(y)); d.writeInt(s15(z));
    return out.toByteArray();
  }

  static byte[] gamma(double gamma) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeBytes("curv");
    d.writeInt(0);
    d.writeInt(1);
    d.writeShort((int) Math.round(gamma * 256)); // u8Fixed8
    return out.toByteArray();
  }

  static int s15(double v) {
    return (int) Math.round(v * 65536);
  }

  static double[] multiply(double[][] m, double[] v) {
    double[] r = new double[3];
    for (int i = 0; i < 3; i++) r[i] = m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2];
    return r;
  }

  static double[][] multiply(double[][] a, double[][] b) {
    double[][] r = new double[3][3];
    for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) r[i][j] += a[i][k] * b[k][j];
    return r;
  }

  static double[][] inverse(double[][] m) {
    double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                 + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    double[][] r = new double[3][3];
    r[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
    r[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
    r[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
    r[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
    r[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
    r[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
    r[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
    r[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
    r[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
    return r;
  }

  static double[] solve(double[][] m, double[] v) {
    return multiply(inverse(m), v);
  }
}
