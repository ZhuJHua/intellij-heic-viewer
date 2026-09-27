package cn.yooss.heic.linux;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * A {@code struct heif_error} other than {@code heif_error_Ok}, as an {@link IOException}. libheif's own message is not
 * available (the struct is read from the first return register only, see {@link Libheif}), so the codes are named here,
 * from {@code enum heif_error_code} and {@code enum heif_suberror_code} of {@code heif_error.h} (values are stable
 * across libheif versions; unknown values are shown as numbers).
 */
final class LibheifException extends IOException {
  private final int code;
  private final int subcode;

  LibheifException(@NotNull String call, int code, int subcode) {
    super(call + " failed: " + describe(code, subcode));
    this.code = code;
    this.subcode = subcode;
  }

  /**
   * Throws unless {@code error} (bytes 0-7 of a {@code struct heif_error} returned by value, i.e. {@code code} in the
   * low and {@code subcode} in the high 32 bits) is {@code heif_error_Ok}.
   */
  static void check(long error, @NotNull String call) throws LibheifException {
    int code = code(error);
    if (code != 0) throw new LibheifException(call, code, subcode(error));
  }

  /** {@code heif_error.code} from bytes 0-7 of the struct. */
  static int code(long error) {
    return (int) error;
  }

  /** {@code heif_error.subcode} from bytes 0-7 of the struct. */
  static int subcode(long error) {
    return (int) (error >>> 32);
  }

  /** {@code enum heif_error_code}. */
  int code() {
    return code;
  }

  /** {@code enum heif_suberror_code}. */
  int subcode() {
    return subcode;
  }

  /** E.g. {@code "Invalid input: No 'ftyp' box (heif_error 2/102)"}. */
  static @NotNull String describe(int code, int subcode) {
    String text = codeName(code);
    if (subcode != 0) text += ": " + subcodeName(subcode);
    return text + " (heif_error " + code + "/" + subcode + ")";
  }

  static @NotNull String codeName(int code) {
    switch (code) {
      case 0: return "Success";
      case 1: return "Input does not exist";
      case 2: return "Invalid input";
      case 3: return "Unsupported file type";
      case 4: return "Unsupported feature";
      case 5: return "Usage error";
      case 6: return "Memory allocation error";
      case 7: return "Decoder plugin error";
      case 8: return "Encoder plugin error";
      case 9: return "Encoding error";
      case 10: return "Color profile does not exist";
      case 11: return "Plugin loading error";
      case 12: return "Canceled";
      case 13: return "End of sequence";
      default: return "Error " + code;
    }
  }

  static @NotNull String subcodeName(int subcode) {
    switch (subcode) {
      case 0: return "Unspecified";
      case 100: return "End of data";
      case 101: return "Invalid box size";
      case 102: return "No 'ftyp' box";
      case 103: return "No 'idat' box";
      case 104: return "No 'meta' box";
      case 105: return "No 'hdlr' box";
      case 106: return "No 'hvcC' box";
      case 107: return "No 'pitm' box";
      case 108: return "No 'ipco' box";
      case 109: return "No 'ipma' box";
      case 110: return "No 'iloc' box";
      case 111: return "No 'iinf' box";
      case 112: return "No 'iprp' box";
      case 113: return "No 'iref' box";
      case 114: return "No 'pict' handler";
      case 115: return "'ipma' box references a nonexisting property";
      case 116: return "No properties assigned to item";
      case 117: return "No item data";
      case 118: return "Invalid grid data";
      case 119: return "Missing grid images";
      case 120: return "Invalid clean aperture";
      case 121: return "Invalid overlay data";
      case 122: return "Overlay image outside of canvas";
      case 123: return "Auxiliary image type unspecified";
      case 124: return "No or invalid primary item";
      case 125: return "No 'infe' box";
      case 126: return "Unknown color profile type";
      case 127: return "Wrong tile image chroma format";
      case 128: return "Invalid fractional number";
      case 129: return "Invalid image size";
      case 130: return "Invalid 'pixi' box";
      case 131: return "No 'av1C' box";
      case 132: return "Wrong tile image pixel depth";
      case 133: return "Unknown NCLX color primaries";
      case 134: return "Unknown NCLX transfer characteristics";
      case 135: return "Unknown NCLX matrix coefficients";
      case 136: return "Invalid region data";
      case 137: return "No 'ispe' property";
      case 1000: return "Security limit exceeded";
      case 1001: return "Compression initialisation error";
      case 2000: return "Nonexisting item referenced";
      case 2001: return "Null pointer argument";
      case 2002: return "Nonexisting image channel referenced";
      case 2003: return "Unsupported plugin version";
      case 2004: return "Unsupported writer version";
      case 2005: return "Unsupported parameter";
      case 2006: return "Invalid parameter value";
      case 2007: return "Invalid property";
      case 2008: return "Item reference cycle";
      case 3000: return "Unsupported codec (no decoder for this compression format)";
      case 3001: return "Unsupported image type";
      case 3002: return "Unsupported data version";
      case 3003: return "Unsupported color conversion";
      case 3004: return "Unsupported item construction method";
      case 3005: return "Unsupported header compression method";
      case 3006: return "Unsupported generic compression method";
      case 3007: return "Unsupported essential property";
      case 4000: return "Unsupported bit depth";
      case 5000: return "Cannot write output data";
      case 6000: return "Plugin loading error";
      case 6001: return "Plugin is not loaded";
      case 6002: return "Cannot read plugin directory";
      case 6003: return "No matching decoder installed";
      default: return "Suberror " + subcode;
    }
  }
}
