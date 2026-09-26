package cn.yooss.heic.ui;

/**
 * Entry points of the decoder UI for the plugin's lifecycle listeners: the check after the reader was registered and
 * the shutdown before the plugin is unloaded.
 */
public final class DecoderUi {
  private DecoderUi() {
  }

  /**
   * After the reader was registered: the decoder check ({@link DecoderStatus#checkInBackground(boolean)}) and the banner
   * of images the heap safety valve decodes smaller ({@link HeicViews#start()}).
   */
  public static void start(boolean prompt) {
    HeicViews.start();
    DecoderStatus.checkInBackground(prompt);
  }

  /**
   * Before the plugin is unloaded (EDT): no more probes, re-checks, banners or balloons; the banners, balloons and
   * popups on screen (whose actions are plugin classes) are removed, so nothing of the plugin stays in the IDE's windows.
   */
  public static void shutDown() {
    try {
      DecoderStatus.shutDown();
    }
    finally {
      try {
        RemedyActions.shutDown(); // the "copied" confirmation and the "More" popup, before their banner goes away
      }
      finally {
        try {
          HeicViews.shutDown();
        }
        finally {
          DecoderPrompt.shutDown();
        }
      }
    }
  }
}
