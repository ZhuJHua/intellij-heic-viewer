package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifImageInfo;
import cn.yooss.heic.backend.PixelPipeline;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Reads the primary image of a HEIC/HEIF file through a {@link HeifBackend} (the system decoder of the running OS).
 * <ul>
 *   <li>The whole stream is read into memory first ({@code stream.length()} may be -1: the IDE disables the
 *   ImageIO disk cache).</li>
 *   <li>{@link #getWidth}/{@link #getHeight} report the display size (orientation applied) from the file's
 *   properties, without decoding pixels.</li>
 *   <li>{@link #read} decodes at full resolution; source subsampling is mapped to a smaller decode size and the source
 *   region is cropped after decoding; destination settings are ignored. An image whose pixels do not fit a Java
 *   {@code int[]} fails with an {@link IOException}.</li>
 *   <li>Opaque images are returned as {@code TYPE_INT_RGB}, images with alpha as non-premultiplied
 *   {@code TYPE_INT_ARGB}.</li>
 *   <li>Any failure of the native layer, including {@link LinkageError}s, is reported as {@link IOException}.</li>
 * </ul>
 */
final class HeicImageReader extends ImageReader {
  private final HeifBackend backend;
  private byte[] data;
  private HeifImageInfo info;

  HeicImageReader(HeicImageReaderSpi provider, HeifBackend backend) {
    super(provider);
    this.backend = backend;
  }

  @Override
  public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
    super.setInput(input, seekForwardOnly, ignoreMetadata);
    data = null;
    info = null;
  }

  @Override
  public int getNumImages(boolean allowSearch) throws IOException {
    requireInput();
    return 1; // the primary image only
  }

  @Override
  public int getWidth(int imageIndex) throws IOException {
    return info(imageIndex).width();
  }

  @Override
  public int getHeight(int imageIndex) throws IOException {
    return info(imageIndex).height();
  }

  @Override
  public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
    int type = info(imageIndex).hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    // Keeps the image's own color model: 24 bits per pixel for TYPE_INT_RGB, which the IDE shows.
    return List.of(new ImageTypeSpecifier(new BufferedImage(1, 1, type))).iterator();
  }

  @Override
  public IIOMetadata getStreamMetadata() {
    return null;
  }

  @Override
  public IIOMetadata getImageMetadata(int imageIndex) {
    return null;
  }

  @Override
  public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
    HeifImageInfo info = info(imageIndex);
    int width = info.width();
    int height = info.height();

    Rectangle region = getSourceRegion(param, width, height); // clipped; throws IAE if empty
    int xSub = param == null ? 1 : Math.max(1, param.getSourceXSubsampling());
    int ySub = param == null ? 1 : Math.max(1, param.getSourceYSubsampling());

    // Full resolution, or what subsampling needs (1/min(sub) of it).
    int fullSide = Math.max(width, height);
    int requestedSide = ceilDiv(fullSide, Math.min(xSub, ySub));
    int side = requestedSide >= fullSide ? 0 : requestedSide;
    int[] size = PixelPipeline.targetSize(width, height, side);
    if ((long) size[0] * size[1] > PixelPipeline.MAX_IMAGE_PIXELS) {
      throw new IOException(String.format(Locale.ROOT, "HEIC image of %dx%d is too large to decode", size[0], size[1]));
    }
    byte[] encoded = bytes();

    processImageStarted(imageIndex);
    BufferedImage decoded;
    try {
      decoded = backend.decode(encoded, side);
    }
    catch (IOException e) {
      throw e;
    }
    catch (RuntimeException | LinkageError e) {
      throw new IOException("Cannot decode HEIC image: " + e, e);
    }
    if (decoded == null) throw new IOException("The decoder returned no image");
    processImageProgress(90f);

    BufferedImage result = cropAndScale(decoded, width, height, region, xSub, ySub);
    if (abortRequested()) {
      processReadAborted();
      return result;
    }
    processImageProgress(100f);
    processImageComplete();
    return result;
  }

  /**
   * Maps the requested source region/subsampling (in full-resolution display coordinates) onto the decoded image,
   * which is smaller than the full resolution for subsampling.
   */
  static BufferedImage cropAndScale(BufferedImage decoded, int width, int height, Rectangle region, int xSub, int ySub) {
    double kx = (double) decoded.getWidth() / width;
    double ky = (double) decoded.getHeight() / height;

    // Target size: exact ImageIO semantics (ceil(region / subsampling)) when the decoded image has enough resolution
    // for the requested subsampling (allowing for the decoder rounding by one pixel), else the decoded scale.
    boolean xExact = decoded.getWidth() >= ceilDiv(width, xSub) - 1;
    boolean yExact = decoded.getHeight() >= ceilDiv(height, ySub) - 1;
    int targetW = xExact ? ceilDiv(region.width, xSub) : Math.max(1, (int) Math.round(region.width * kx));
    int targetH = yExact ? ceilDiv(region.height, ySub) : Math.max(1, (int) Math.round(region.height * ky));

    int cx = clamp((int) Math.floor(region.x * kx), 0, decoded.getWidth() - 1);
    int cy = clamp((int) Math.floor(region.y * ky), 0, decoded.getHeight() - 1);
    int cw = clamp((int) Math.round(region.width * kx), 1, decoded.getWidth() - cx);
    int ch = clamp((int) Math.round(region.height * ky), 1, decoded.getHeight() - cy);

    boolean fullImage = cx == 0 && cy == 0 && cw == decoded.getWidth() && ch == decoded.getHeight();
    if (fullImage && cw == targetW && ch == targetH) return decoded;

    BufferedImage out = new BufferedImage(targetW, targetH, decoded.getType());
    if (cw == targetW && ch == targetH) {
      // Plain crop: copy the pixels so that the (possibly large) decoded image can be garbage collected.
      out.getRaster().setRect(decoded.getRaster().createChild(cx, cy, cw, ch, 0, 0, null));
      return out;
    }
    Graphics2D g = out.createGraphics();
    try {
      g.setComposite(AlphaComposite.Src);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(decoded, 0, 0, targetW, targetH, cx, cy, cx + cw, cy + ch, null);
    }
    finally {
      g.dispose();
    }
    return out;
  }

  private HeifImageInfo info(int imageIndex) throws IOException {
    checkIndex(imageIndex);
    if (info == null) {
      try {
        info = backend.readInfo(bytes());
      }
      catch (IOException e) {
        throw e;
      }
      catch (RuntimeException | LinkageError e) {
        throw new IOException("Cannot read HEIC image properties: " + e, e);
      }
      if (info == null) throw new IOException("The decoder returned no image properties");
    }
    return info;
  }

  private void checkIndex(int imageIndex) {
    requireInput();
    if (imageIndex != 0) throw new IndexOutOfBoundsException("Only the primary image (index 0) is available: " + imageIndex);
  }

  private ImageInputStream requireInput() {
    if (!(input instanceof ImageInputStream stream)) throw new IllegalStateException("Input not set");
    return stream;
  }

  /** Reads the rest of the stream once; {@code length()} may be unknown (-1). */
  private byte[] bytes() throws IOException {
    if (data != null) return data;
    ImageInputStream stream = requireInput();
    long length = stream.length();
    long position = stream.getStreamPosition();
    long remaining = length >= 0 ? length - position : -1;
    if (remaining > HeicImageReaderSpi.MAX_INPUT_BYTES) throw new IOException("HEIC file too large: " + length + " bytes");

    ByteArrayOutputStream out = new ByteArrayOutputStream(remaining > 0 ? (int) remaining : 1 << 16);
    byte[] buffer = new byte[1 << 16];
    long total = 0;
    int read;
    while ((read = stream.read(buffer)) > 0) {
      total += read;
      if (total > HeicImageReaderSpi.MAX_INPUT_BYTES) throw new IOException("HEIC file too large: more than " + total + " bytes");
      out.write(buffer, 0, read);
    }
    if (total == 0) throw new IOException("Empty HEIC stream");
    data = out.toByteArray();
    return data;
  }

  @Override
  public void reset() {
    super.reset();
    data = null;
    info = null;
  }

  @Override
  public void dispose() {
    data = null;
    info = null;
    super.dispose();
  }

  private static int ceilDiv(int a, int b) {
    return (int) ((a + (long) b - 1) / b);
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
