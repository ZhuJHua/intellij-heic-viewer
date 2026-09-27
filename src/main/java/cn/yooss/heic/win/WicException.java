package cn.yooss.heic.win;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/** A failed COM call of the Windows backend: the {@link IOException} carries the {@code HRESULT}. */
public final class WicException extends IOException {
  private final int hresult;
  private final String call;

  public WicException(@NotNull String call, int hresult) {
    super(call + " failed: " + Hresult.describe(hresult));
    this.call = call;
    this.hresult = hresult;
  }

  public int hresult() {
    return hresult;
  }

  /** The failed call, e.g. {@code IWICImagingFactory::CreateDecoderFromStream}. */
  public @NotNull String call() {
    return call;
  }
}
