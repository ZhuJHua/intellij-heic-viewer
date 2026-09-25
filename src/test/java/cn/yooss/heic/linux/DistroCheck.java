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
    failures += expect(backend.decode(Fixtures.bytes("rgb_libheif.heic"), 0), "600x400 TL=red TR=green BL=blue BR=white marker=TL");
    failures += expect(backend.decode(Fixtures.bytes("exif6_apple.heic"), 0), "400x600 TL=blue TR=red BL=white BR=green marker=TR");
    failures += expect(backend.decode(Fixtures.bytes("fliph_imir.heic"), 0), "600x400 TL=green TR=red BL=white BR=blue marker=TR");
    failures += expect(backend.decode(Fixtures.bytes("grid_libheif.heic"), 0), "600x400 TL=red TR=green BL=blue BR=white marker=TL");
    failures += expect(backend.decode(Fixtures.bytes("seq.heics"), 300), "300x200 TL=red TR=green BL=blue BR=white marker=TL");
    String thumbnail = Fixtures.layout(backend.decodeThumbnail(Fixtures.bytes("thumb_irot.heic"), 64));
    failures += check("embedded thumbnail", thumbnail.startsWith("43x64 TL=blue TR=red BL=white BR=green"), thumbnail);

    BufferedImage alpha = backend.decode(Fixtures.bytes("alpha_libheif.heic"), 0);
    int redAt128 = alpha.getRGB(150, 150);
    failures += check("alpha", alpha.getType() == BufferedImage.TYPE_INT_ARGB && Math.abs((redAt128 >>> 24) - 128) <= 4,
                      Integer.toHexString(redAt128));
    BufferedImage tenBit = backend.decode(Fixtures.bytes("ten_bit.heic"), 0);
    double tenBitDifference = Fixtures.meanDifference(tenBit, Fixtures.png("rgb16.png"));
    failures += check("10-bit", tenBitDifference < 1.5, "mean difference " + tenBitDifference);
    int icc = backend.decode(Fixtures.bytes("icc_wide.heic"), 0).getRGB(100, 75); // (200, 60, 60) in the wide space
    failures += check("ICC profile", Math.abs(((icc >> 16) & 255) - 219) <= 4 && Math.abs(((icc >> 8) & 255) - 39) <= 4,
                      Integer.toHexString(icc));
    System.out.println(failures == 0 ? "DECODE OK" : "DECODE FAILED: " + failures);
    return failures == 0 ? 0 : 1;
  }

  private static int expect(BufferedImage image, String layout) {
    return check(layout, Fixtures.layout(image).equals(layout), Fixtures.layout(image));
  }

  private static int check(String name, boolean ok, String actual) {
    System.out.println((ok ? "ok      " : "FAILED  ") + name + (ok ? "" : ": " + actual));
    return ok ? 0 : 1;
  }
}
