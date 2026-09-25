package cn.yooss.heic.win;

/**
 * The Microsoft Store packages that make Windows decode HEIC, as listed by the Store catalog
 * ({@code https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=<id>&market=US&languages=en-US}) and Microsoft's
 * "HEIF extension codec" documentation (learn.microsoft.com/windows/win32/wic/heif-codec, which links 9PMMSR1CGPWG):
 * <table>
 *   <caption>Store packages</caption>
 *   <tr><th>Product id</th><th>Title</th><th>Package family</th><th>Price</th><th>Minimum Windows</th></tr>
 *   <tr><td>9PMMSR1CGPWG</td><td>HEIF Image Extension</td><td>Microsoft.HEIFImageExtension_8wekyb3d8bbwe</td>
 *   <td>free</td><td>10.0.17763 (Windows 10 1809)</td></tr>
 *   <tr><td>9NMZLZ57R3T7</td><td>HEVC Video Extensions</td><td>Microsoft.HEVCVideoExtensions_8wekyb3d8bbwe</td>
 *   <td>US$0.99</td><td>10.0.16299 (Windows 10 1709)</td></tr>
 *   <tr><td>9N4WGH0Z6VHQ</td><td>HEVC Video Extensions from Device Manufacturer</td>
 *   <td>Microsoft.HEVCVideoExtension_8wekyb3d8bbwe</td><td>free, but only preinstalled by PC makers (the Store offers
 *   no purchase action for it)</td><td>10.0.16299</td></tr>
 * </table>
 * Which systems have them preinstalled depends on the Windows image: the GitHub-hosted Windows 11 Enterprise 25H2
 * (arm64) image has the HEIF Image Extension and the device-manufacturer HEVC package provisioned, the Windows Server
 * 2025 image has neither (and no Microsoft Store). Elsewhere it depends on the Windows edition and the PC maker; where
 * no HEVC package came with the PC, the paid one is the one a user can get.
 */
final class WindowsCodecs {
  private WindowsCodecs() {
  }

  static final String HEIF_PRODUCT_ID = "9PMMSR1CGPWG";
  static final String HEVC_PRODUCT_ID = "9NMZLZ57R3T7";
  static final String HEVC_DEVICE_MANUFACTURER_PRODUCT_ID = "9N4WGH0Z6VHQ";

  /** Opens in any browser; the page offers to continue in the Microsoft Store app. */
  static final String HEIF_STORE_URL = "https://apps.microsoft.com/detail/" + HEIF_PRODUCT_ID;
  static final String HEVC_STORE_URL = "https://apps.microsoft.com/detail/" + HEVC_PRODUCT_ID;
  /** Opens the product page in the Microsoft Store app directly. */
  static final String HEIF_STORE_APP_URL = "ms-windows-store://pdp/?ProductId=" + HEIF_PRODUCT_ID;
  static final String HEVC_STORE_APP_URL = "ms-windows-store://pdp/?ProductId=" + HEVC_PRODUCT_ID;

  /** Installs the (free) HEIF Image Extension with the Windows Package Manager from the Microsoft Store source. */
  static final String HEIF_WINGET_COMMAND =
    "winget install --id " + HEIF_PRODUCT_ID + " --source msstore --accept-package-agreements";
}
