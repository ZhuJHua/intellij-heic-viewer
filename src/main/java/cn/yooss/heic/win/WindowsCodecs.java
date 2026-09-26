package cn.yooss.heic.win;

/**
 * The Microsoft Store packages that make Windows decode HEIC: the HEIF Image Extension ({@link #HEIF_PRODUCT_ID}) and
 * the HEVC Video Extensions ({@link #HEVC_PRODUCT_ID}). The backend's statuses carry the Store app page and, for the
 * HEIF Image Extension, the winget command; {@code backend.HeifRemedies} turns them into actions.
 */
public final class WindowsCodecs {
  private WindowsCodecs() {
  }

  public static final String HEIF_PRODUCT_ID = "9PMMSR1CGPWG";
  public static final String HEVC_PRODUCT_ID = "9NMZLZ57R3T7";

  /** Opens in any browser; the page offers to continue in the Microsoft Store app. */
  public static final String HEIF_STORE_URL = "https://apps.microsoft.com/detail/" + HEIF_PRODUCT_ID;
  public static final String HEVC_STORE_URL = "https://apps.microsoft.com/detail/" + HEVC_PRODUCT_ID;
  /** Opens the product page in the Microsoft Store app directly. */
  public static final String HEIF_STORE_APP_URL = "ms-windows-store://pdp/?ProductId=" + HEIF_PRODUCT_ID;
  public static final String HEVC_STORE_APP_URL = "ms-windows-store://pdp/?ProductId=" + HEVC_PRODUCT_ID;

  /** Installs the (free) HEIF Image Extension with the Windows Package Manager from the Microsoft Store source. */
  public static final String HEIF_WINGET_COMMAND =
    "winget install --id " + HEIF_PRODUCT_ID + " --source msstore --accept-package-agreements";
}
