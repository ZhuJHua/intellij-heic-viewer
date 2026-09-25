package cn.yooss.heic.ui;

/**
 * Entry points of the decoder UI for the plugin's lifecycle listeners: the check after the reader was registered and
 * the shutdown before the plugin is unloaded.
 */
public final class DecoderUi {
  private DecoderUi() {
  }

  /** See {@link DecoderStatus#checkInBackground(boolean)}. */
  public static void checkInBackground(boolean prompt) {
    DecoderStatus.checkInBackground(prompt);
  }

  /**
   * Before the plugin is unloaded (EDT): no more probes, re-checks, banners or balloons; the banners and balloons on
   * screen (whose actions are plugin classes) are removed, so nothing of the plugin stays in the IDE's windows.
   */
  public static void shutDown() {
    try {
      DecoderStatus.shutDown();
      HeicViews.shutDown();
    }
    finally {
      try {
        RemedyActions.shutDown();
      }
      finally {
        DecoderPrompt.shutDown();
      }
    }
  }
}
