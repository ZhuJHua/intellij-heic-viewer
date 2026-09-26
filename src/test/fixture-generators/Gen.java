/*
 * Generates the PNG sources of the synthetic test fixtures in src/test/resources/fixtures:
 *   rgb.png    600x400 quadrants (TL red, TR green, BL blue, BR white), black 40x40 marker top-left, brown centre patch
 *   alpha.png  400x300, four 100 px columns: transparent, red a=128, blue a=255, green a=64
 *   rgb16.png  512x256 16-bit RGB gradient (source of the 10-bit HEIC fixtures)
 * Run: java Gen.java <outputDir>
 *
 * The HEIC/HEIF/AVIF fixtures were encoded from these PNGs on macOS 26:
 *   sips -s format heic ...                  -> rgb_sips.heic, alpha_sips.heic, rgb16_sips.heic (10-bit, brand heix),
 *                                               seq.heics (public.heics), rgb_sips.avif
 *   heif-enc (libheif 1.23.5, Homebrew)      -> rgb_libheif.heic, rot90_irot.heic (irot), fliph_imir.heic (imir),
 *                                               grid_libheif.heic (5x4 grid of 128 px tiles), multi.heic (2 images),
 *                                               ten_bit.heic, alpha_libheif.heic, rgb.avif, alpha.avif,
 *                                               thumb_irot.heic (heif-enc -q 90 -t 96 --rotate-cw 90 rgb.png: irot and
 *                                               an embedded 64x96 thumbnail)
 *   writeorient.swift (CGImageDestination)   -> exif3/5/6_apple.heic (Apple writes irot/imir plus EXIF orientation)
 *   header_only.heic                          = the first 64 bytes of rgb_sips.heic (ftyp + a cut meta box)
 *   garbage.heic                              = plain text
 * GenBands.java produces bands.png; bands_2000x1200.heic = sips -s format heic, bands_exif6.heic = writeorient ... 6.
 * quadrants_4096x3072.heic (12.6 MP, 12 kB) = sips -s format heic of
 *   magick -size 4096x3072 xc:white -fill red -draw "rectangle 0,0 2047,1535" -fill '#00ff00'
 *     -draw "rectangle 2048,0 4095,1535" -fill blue -draw "rectangle 0,1536 2047,3071" -fill black
 *     -draw "rectangle 0,0 272,272" PNG24:quadrants.png
 *   (the layout of rgb.png: TL red, TR green, BL blue, BR white, black marker top-left; an 8-bit 8x6 grid of 512 px tiles).
 * GenIcc.java produces the ICC profile and PNG of icc_wide.heic (colors in a wide-gamut space, embedded ICC profile).
 *
 * Color fixtures of the Windows workarounds in src/test/resources/cn/yooss/heic/win (heif-enc 1.23.5, macOS 26 sips):
 *   heif-enc -q 90 --matrix_coefficients=1 rgb.png   -> rgb_bt709.heic   (single image, BT.709 matrix)
 *   heif-enc -q 90 --full_range_flag=0 rgb.png       -> rgb_limited.heic (single image, BT.601 limited range)
 *   heif-enc -q 90 -p chroma=444 rgb.png             -> rgb_444.heic     (single image, 4:4:4)
 *   smooth.png: 320x240, smooth sine/cosine gradients in R, G and B with eight saturated patches (a downscaled 4032x3024
 *   test image), for transfer-curve errors that the quadrant fixtures cannot show;
 *   sips -s format heic smooth.png                   -> smooth_sips.heic (single image, nclx 2/2/6/full as macOS writes)
 *   heif-enc -q 80 --cut-tiles 128 --transfer_characteristic=1 smooth.png -> smooth_grid_tc1.heic (grid, BT.709 curve)
 */
import java.awt.*;
import java.awt.image.*;
import java.io.File;
import javax.imageio.ImageIO;

public class Gen {
  public static void main(String[] a) throws Exception {
    File dir = new File(a[0]);
    // 600x400 opaque, quadrants: TL red, TR green, BL blue, BR white; black 40x40 marker in TL corner
    BufferedImage rgb = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = rgb.createGraphics();
    g.setColor(new Color(255, 0, 0)); g.fillRect(0, 0, 300, 200);
    g.setColor(new Color(0, 255, 0)); g.fillRect(300, 0, 300, 200);
    g.setColor(new Color(0, 0, 255)); g.fillRect(0, 200, 300, 200);
    g.setColor(new Color(255, 255, 255)); g.fillRect(300, 200, 300, 200);
    g.setColor(Color.BLACK); g.fillRect(0, 0, 40, 40);
    g.setColor(new Color(128, 64, 32)); g.fillRect(260, 180, 80, 40); // brown center patch
    g.dispose();
    ImageIO.write(rgb, "png", new File(dir, "rgb.png"));

    // 400x300 with alpha: col0 transparent, col1 red a=128, col2 blue a=255, col3 green a=64
    BufferedImage al = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
    int[] cols = {0x00000000, 0x80FF0000, 0xFF0000FF, 0x4000FF00};
    for (int y = 0; y < 300; y++) for (int x = 0; x < 400; x++) al.setRGB(x, y, cols[x / 100]);
    ImageIO.write(al, "png", new File(dir, "alpha.png"));

    // 16-bit RGB gradient 512x256 for 10-bit encode
    ColorModel cm = new ComponentColorModel(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB),
        new int[]{16,16,16}, false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
    WritableRaster r = cm.createCompatibleWritableRaster(512, 256);
    for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++) {
      r.setPixel(x, y, new int[]{x * 128, y * 256, 65535 - x * 128});
    }
    ImageIO.write(new BufferedImage(cm, r, false, null), "png", new File(dir, "rgb16.png"));
  }
}
