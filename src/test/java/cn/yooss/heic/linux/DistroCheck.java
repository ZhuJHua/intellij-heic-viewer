package cn.yooss.heic.linux;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackends;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;

/**
 * Command-line checks for the <i>Linux install commands</i> CI job, which runs them in containers of several
 * distributions with the plugin's and the tests' classes, the JNA jar and a JDK mounted:
 * <ul>
 *   <li>{@code command <REASON>}: prints the install command the plugin suggests on this distribution (exit 3 if
 *   none);</li>
 *   <li>{@code status [expected]}: prints the status of the plugin's backend ({@link HeifBackends#current()}, exactly as
 *   in the IDE) and fails unless it is {@code expected} ({@code available} or a reason name);</li>
 *   <li>{@code decode}: decodes fixtures (orientation, alpha, 10-bit, grid, ICC profile, embedded thumbnail) and
 *   fails on a wrong size or layout.</li>
 * </ul>
 */
public final class DistroCheck {
  private DistroCheck() {
  }

  public static void main(String[] args) throws Exception {
    int exitCode;
    switch (args.length > 0 ? args[0] : "") {
      case "command":
        exitCode = command(HeifBackendStatus.Reason.valueOf(args[1]));
        break;
      case "status":
        exitCode = status(args.length > 1 ? args[1] : null);
        break;
      case "decode":
        exitCode = decode();
        break;
      default:
        System.err.println("usage: DistroCheck command <REASON> | status [available|<REASON>] | decode");
        exitCode = 2;
    }
    System.exit(exitCode);
  }

  private static int command(HeifBackendStatus.Reason reason) {
    LinuxDistribution distro = LinuxDistribution.current();
    String command = LibheifRemedy.installCommand(reason, distro);
    System.err.println(distro + ": " + (command != null ? command : "no install command"));
    if (command == null) return 3;
    System.out.println(command);
    return 0;
  }

  private static int status(String expected) {
    HeifBackend backend = HeifBackends.current();
    HeifBackendStatus status = backend.status();
    System.out.println("STATUS " + status);
    if (expected == null) return 0;
    String actual = status.isAvailable() ? "available" : String.valueOf(status.reason());
    if (actual.toLowerCase(Locale.ROOT).equals(expected.toLowerCase(Locale.ROOT))) return 0;
    System.out.println("FAILED: expected " + expected + ", was " + actual);
    return 1;
  }

  private static int decode() throws IOException {
    HeifBackend backend = HeifBackends.current();
    int failures = 0;
    failures += layout(backend, "rgb_sips.heic", 0, "600x400 TL=red TR=green BL=blue BR=white marker=TL");
    failures += layout(backend, "rgb_libheif.heic", 0, "600x400 TL=red TR=green BL=blue BR=white marker=TL");
    failures += layout(backend, "exif6_apple.heic", 0, "400x600 TL=blue TR=red BL=white BR=green marker=TR");
    failures += layout(backend, "fliph_imir.heic", 0, "600x400 TL=green TR=red BL=white BR=blue marker=TR");
    failures += layout(backend, "grid_libheif.heic", 0, "600x400 TL=red TR=green BL=blue BR=white marker=TL");
    failures += layout(backend, "multi.heic", 0, "600x400 TL=red TR=green BL=blue BR=white marker=TL");
    failures += layout(backend, "seq.heics", 300, "300x200 TL=red TR=green BL=blue BR=white marker=TL");
    failures += check("thumb_irot.heic (embedded thumbnail)", () -> {
      String layout = Fixtures.layout(backend.decodeThumbnail(Fixtures.bytes("thumb_irot.heic"), 64));
      return layout.startsWith("43x64 TL=blue TR=red BL=white BR=green") ? null : layout;
    });
    for (String name : new String[]{"alpha_sips.heic", "alpha_libheif.heic"}) {
      failures += check(name + " (alpha)", () -> {
        BufferedImage alpha = backend.decode(Fixtures.bytes(name), 0);
        int redAt128 = alpha.getRGB(150, 150);
        boolean ok = alpha.getType() == BufferedImage.TYPE_INT_ARGB && Math.abs((redAt128 >>> 24) - 128) <= 4;
        return ok ? null : Integer.toHexString(redAt128);
      });
    }
    for (String name : new String[]{"rgb16_sips.heic", "ten_bit.heic"}) {
      failures += check(name + " (10-bit)", () -> {
        double difference = Fixtures.meanDifference(backend.decode(Fixtures.bytes(name), 0), Fixtures.png("rgb16.png"));
        return difference < 1.5 ? null : "mean difference " + difference;
      });
    }
    failures += check("icc_wide.heic (ICC profile)", () -> {
      int icc = backend.decode(Fixtures.bytes("icc_wide.heic"), 0).getRGB(100, 75); // (200, 60, 60) in the wide space
      boolean ok = Math.abs(((icc >> 16) & 255) - 219) <= 4 && Math.abs(((icc >> 8) & 255) - 39) <= 4;
      return ok ? null : Integer.toHexString(icc);
    });
    failures += check("decode limit (grid_libheif.heic)", () -> decodeLimit(backend));
    System.out.println(failures == 0 ? "DECODE OK" : "DECODE FAILED: " + failures);
    return failures == 0 ? 0 : 1;
  }

  /**
   * The decode limit of {@link LibheifDecoder} (made small): checked in Java on the declared size, and by libheif on the
   * image it builds when the file declares a small size ({@code heif_context_set_maximum_image_size_limit}, whose
   * meaning differs between libheif versions).
   */
  private static String decodeLimit(HeifBackend backend) throws IOException {
    Libheif lib = ((LibheifHeifBackend) backend).library();
    if (lib == null) return "libheif is not loaded";
    byte[] grid = Fixtures.bytes("grid_libheif.heic"); // 600x400
    String fits = Fixtures.layout(new LibheifDecoder(lib, 1024).decode(grid, 0, false));
    if (!fits.startsWith("600x400")) return "within the limit: " + fits;
    try {
      new LibheifDecoder(lib, 256).decode(grid, 0, false);
      return "600x400 decoded despite a limit of 256x256";
    }
    catch (IOException e) {
      if (!e.getMessage().contains("too large to decode")) return "Java check: " + e;
    }
    if (!lib.canLimitDecodeSize()) {
      System.out.println("        libheif " + lib.version() + " has no heif_context_set_maximum_image_size_limit");
      return null;
    }
    byte[] claimsSmall = TestLibheif.withIspe(grid, 600, 400, 64, 64);
    try {
      new LibheifDecoder(lib, 256).decode(claimsSmall, 0, false);
      return "libheif " + lib.version() + " decoded a 600x400 grid that declares 64x64 despite a limit of 256x256";
    }
    catch (LibheifException e) {
      System.out.println("        libheif " + lib.version() + ": " + e.getMessage());
      return e.code() == 6 && e.subcode() == 1000 ? null : "libheif " + lib.version() + ": " + e.getMessage();
    }
  }

  private interface Check {
    /** {@code null} if the result is right, else what was found instead. */
    String run() throws Exception;
  }

  private static int layout(HeifBackend backend, String name, int maxPixelSize, String expected) {
    return check(name, () -> {
      String layout = Fixtures.layout(backend.decode(Fixtures.bytes(name), maxPixelSize));
      return layout.equals(expected) ? null : layout;
    });
  }

  /**
   * Runs a check; a failure counts unless the check's name starts with one of the comma-separated prefixes of
   * {@code -Dheic.check.known} (limitations of an old libheif, documented in the workflow).
   */
  private static int check(String name, Check check) {
    String problem;
    try {
      problem = check.run();
    }
    catch (Exception e) {
      problem = e.toString();
    }
    if (problem == null) {
      System.out.println("ok      " + name);
      return 0;
    }
    for (String known : System.getProperty("heic.check.known", "").split(",")) {
      if (!known.trim().isEmpty() && name.startsWith(known.trim())) {
        System.out.println("KNOWN   " + name + ": " + problem);
        return 0;
      }
    }
    System.out.println("FAILED  " + name + ": " + problem);
    return 1;
  }
}
