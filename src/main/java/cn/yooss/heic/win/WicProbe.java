package cn.yooss.heic.win;

import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.PixelPipeline;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The availability probe of the Windows backend: what WIC and Media Foundation report, and the
 * {@link HeifBackendStatus} that follows ({@link #toStatus()}, pure Java, so the decision table is tested on every OS).
 * <p>
 * Observed behaviour (Windows 11 25H2 and Windows Server 2025, HEIF Image Extension 1.2.36, HEVC Video Extension
 * 2.4.45, in CI):
 * <table>
 *   <caption>What each component's absence looks like</caption>
 *   <tr><th>Missing</th><th>{@code CreateDecoder(GUID_ContainerFormatHeif)}</th><th>decoding HEIC data</th>
 *   <th>{@code MFTEnumEx} HEVC decoders</th></tr>
 *   <tr><td>HEIF Image Extension</td><td>{@code WINCODEC_ERR_COMPONENTINITIALIZEFAILURE}</td>
 *   <td>{@code CreateDecoderFromStream}: {@code WINCODEC_ERR_COMPONENTINITIALIZEFAILURE}</td><td>(any)</td></tr>
 *   <tr><td>HEVC Video Extension</td><td>{@code S_OK}</td>
 *   <td>size and metadata work, {@code CopyPixels}: {@code MF_E_TOPO_CODEC_NOT_FOUND}</td><td>0</td></tr>
 *   <tr><td>nothing</td><td>{@code S_OK}</td><td>works</td><td>1 (e.g. "HEVCVideoExtension")</td></tr>
 * </table>
 * The decisive check is decoding a tiny embedded HEIC ({@link #SAMPLE_BASE64}, 128x64 with {@code irot} 90 degrees, left half
 * red, right half blue): it proves the whole chain (WIC, HEIF decoder, HEVC transform, this plugin's binding). The first
 * decode in a process takes about one to two seconds (the Store packages are activated and the codec is loaded), later
 * ones a few milliseconds, so the probe costs what the first image would cost anyway; it runs on a background thread.
 */
final class WicProbe {
  /** {@code MFT_ENUM_FLAG_SYNCMFT | ASYNCMFT | HARDWARE | LOCALMFT | SORTANDFILTER} (mfapi.h). */
  static final int MFT_ENUM_FLAGS = 0x01 | 0x02 | 0x04 | 0x10 | 0x40;

  /**
   * 428-byte HEIC encoded with libheif 1.23.5 ({@code heif-enc --rotate-cw 90 -q 30}) from a 128x64 image whose left
   * half is red and right half blue: stored 128x64 with {@code irot} 270 (counter-clockwise), displayed 64x128 with red
   * on top and blue at the bottom. {@code WicProbeTest} checks it with the macOS decoder.
   */
  static final String SAMPLE_BASE64 =
    "AAAAHGZ0eXBoZWljAAAAAG1pZjFoZWljbWlhZgAAAWBtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABwaWN0AAAAAAAAAAAAAAAAAAAA"
    + "ACJpbG9jAAAAAERAAAEAAQAAAAABhAABAAAAAAAAACgAAAAjaWluZgAAAAAAAQAAABVpbmZlAgAAAAABAABodmMxAAAAAA5waXRt"
    + "AAAAAAABAAAA4GlwcnAAAADAaXBjbwAAAHhodmNDAQNwAAAAAAAAAAAAHvAA/P34+AAADwNgAAEAGEABDAH//wNwAAADAJAAAAMA"
    + "AAMAHroCQGEAAQArQgEBA3AAAAMAkAAAAwAAAwAeoBAgQWW6kkprm4CGgwIAAAMAMgAAAwACEGIAAQAHRAHBcrAiQAAAABNjb2xy"
    + "bmNseAABAA0ABoAAAAAUaXNwZQAAAAAAAACAAAAAQAAAABBwaXhpAAAAAAMICAgAAAAJaXJvdAMAAAAYaXBtYQAAAAAAAAABAAEF"
    + "gQIDBIUAAAAwbWRhdAAAACQoAa8ZgPd8hTf/+KGH/mta/6/BXita7//cdrX62zX/hxPRrJg=";

  static byte[] sample() {
    return Base64.getDecoder().decode(SAMPLE_BASE64);
  }

  /** The native binding, e.g. {@code "JNA 5.17.0 (amd64)"}. */
  String bridge = "?";
  /** {@code HRESULT} of {@code IWICImagingFactory::CreateDecoder(GUID_ContainerFormatHeif)}, if it could be called. */
  @Nullable Integer createDecoderHr;
  /** Why {@code CreateDecoder} could not be called (COM or the factory failed), or {@code null}. */
  @Nullable String createDecoderProblem;
  /** Why the sample could not be decoded, or {@code null}. */
  @Nullable IOException sampleFailure;
  /** What is wrong with the decoded sample, or {@code null}. */
  @Nullable String sampleProblem;
  /** Size of the decoded sample, e.g. {@code 64x128}. */
  @Nullable String sampleSize;
  /** Friendly names of the HEVC decoders Media Foundation enumerates; {@code null} if they could not be enumerated. */
  @Nullable List<String> hevcDecoders;
  /** Why the HEVC decoders could not be enumerated (Media Foundation missing, an error), or {@code null}. */
  @Nullable String hevcProblem;

  /** Runs every check (never throws for a failed check; a {@link LinkageError} of the binding propagates). */
  static @NotNull WicProbe run(@NotNull WicDecoder decoder) {
    WicProbe probe = new WicProbe();
    WinApi api = decoder.api();
    probe.bridge = api.name();
    try (WicDecoder.Session session = new WicDecoder.Session(api)) {
      long[] heif = new long[1];
      probe.createDecoderHr = api.createDecoder(session.factory(), Guids.GUID_ContainerFormatHeif, heif);
      session.own(heif[0]);
    }
    catch (IOException e) {
      probe.createDecoderProblem = e.getMessage();
    }
    try {
      BufferedImage image = decoder.decode(sample(), 0, true, PixelPipeline.STRIP_PIXELS);
      probe.sampleSize = image.getWidth() + "x" + image.getHeight();
      probe.sampleProblem = checkSample(image);
    }
    catch (IOException e) {
      probe.sampleFailure = e;
    }
    try {
      List<String> names = new ArrayList<>();
      int hr = api.enumerateVideoDecoders(Guids.MFVideoFormat_HEVC, MFT_ENUM_FLAGS, names);
      if (Hresult.succeeded(hr)) probe.hevcDecoders = names;
      else probe.hevcProblem = "MFTEnumEx failed: " + Hresult.describe(hr);
    }
    catch (UnsatisfiedLinkError e) {
      probe.hevcProblem = "Media Foundation is not available (mfplat.dll: " + e.getMessage() + ")";
    }
    catch (RuntimeException e) {
      probe.hevcProblem = "Cannot enumerate the HEVC decoders: " + e;
    }
    return probe;
  }

  /**
   * {@code null} if {@code image} is the decoded {@link #SAMPLE_BASE64 sample} (red half, blue half, oriented or not),
   * else what is wrong with it.
   */
  static @Nullable String checkSample(@NotNull BufferedImage image) {
    int w = image.getWidth(), h = image.getHeight();
    int[] first, second;
    if (w == 64 && h == 128) { // irot applied: red on top, blue at the bottom
      first = mean(image, 8, 8, 56, 56);
      second = mean(image, 8, 72, 56, 120);
    }
    else if (w == 128 && h == 64) { // irot not applied by the decoder and not reported either
      first = mean(image, 8, 8, 56, 56);
      second = mean(image, 72, 8, 120, 56);
    }
    else {
      return "the test image decoded to " + w + "x" + h + " instead of 64x128";
    }
    boolean red = first[0] > 170 && first[1] < 90 && first[2] < 90;
    boolean blue = second[2] > 170 && second[0] < 90 && second[1] < 90;
    if (!red || !blue) {
      return String.format(Locale.ROOT, "the test image decoded to unexpected colors (%s and %s instead of red and blue)",
                           rgb(first), rgb(second));
    }
    return null;
  }

  private static int[] mean(BufferedImage image, int x0, int y0, int x1, int y1) {
    long r = 0, g = 0, b = 0, n = 0;
    for (int y = y0; y < y1; y++) {
      for (int x = x0; x < x1; x++) {
        int p = image.getRGB(x, y);
        r += (p >> 16) & 255;
        g += (p >> 8) & 255;
        b += p & 255;
        n++;
      }
    }
    return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
  }

  private static String rgb(int[] c) {
    return String.format(Locale.ROOT, "#%02x%02x%02x", c[0], c[1], c[2]);
  }

  boolean heifDecoderMissing() {
    if (createDecoderHr != null && WicDecoder.isHeifDecoderMissing(createDecoderHr)) return true;
    return sampleFailure instanceof WicException
           && ((WicException) sampleFailure).call().startsWith("IWICImagingFactory::CreateDecoderFromStream")
           && WicDecoder.isHeifDecoderMissing(((WicException) sampleFailure).hresult());
  }

  boolean hevcDecoderMissing() {
    if (sampleFailure == null) return false;
    if (sampleFailure instanceof WicException && WicDecoder.isHevcDecoderMissing(((WicException) sampleFailure).hresult())) {
      return true;
    }
    return hevcDecoders != null && hevcDecoders.isEmpty();
  }

  /** The status these results mean. */
  @NotNull HeifBackendStatus toStatus() {
    String details = details();
    if (sampleFailure == null && sampleProblem == null) {
      return HeifBackendStatus.available("Windows Imaging Component with the HEIF Image Extension through " + details);
    }
    if (heifDecoderMissing()) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.WINDOWS_HEIF_EXTENSION_MISSING,
                                           "WIC has no HEIF decoder (install the HEIF Image Extension, Microsoft Store "
                                           + WindowsCodecs.HEIF_PRODUCT_ID + "); " + details)
        .withInstallUrl(WindowsCodecs.HEIF_STORE_URL)
        .withInstallCommand(WindowsCodecs.HEIF_WINGET_COMMAND);
    }
    if (hevcDecoderMissing()) {
      return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.WINDOWS_HEVC_EXTENSION_MISSING,
                                           "The HEIF decoder has no HEVC codec (install the HEVC Video Extensions, "
                                           + "Microsoft Store " + WindowsCodecs.HEVC_PRODUCT_ID + "); " + details)
        .withInstallUrl(WindowsCodecs.HEVC_STORE_URL);
    }
    return HeifBackendStatus.unavailable(HeifBackendStatus.Reason.ERROR, "The Windows HEIF decoder does not work: " + details);
  }

  /** Everything found, for idea.log. */
  @NotNull String details() {
    StringBuilder text = new StringBuilder(bridge);
    text.append("; CreateDecoder(HEIF): ")
      .append(createDecoderHr != null ? Hresult.describe(createDecoderHr) : "not called (" + createDecoderProblem + ")");
    text.append("; test image: ");
    if (sampleFailure != null) text.append(sampleFailure.getMessage());
    else if (sampleProblem != null) text.append(sampleProblem);
    else text.append("decoded (").append(sampleSize).append(")");
    text.append("; HEVC decoders: ");
    if (hevcDecoders != null) text.append(hevcDecoders.isEmpty() ? "none" : String.join(", ", hevcDecoders));
    else text.append(hevcProblem);
    return text.toString();
  }
}
