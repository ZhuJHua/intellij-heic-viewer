package cn.yooss.heic.ui;

import cn.yooss.heic.Fixtures;
import cn.yooss.heic.HeicBundle;
import cn.yooss.heic.backend.HeifBackend;
import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifBackends;
import cn.yooss.heic.backend.HeifRemedies;
import cn.yooss.heic.backend.HeifRemedy;
import cn.yooss.heic.win.WindowsCodecs;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LeakHunter;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.UiInterceptors;
import org.intellij.images.editor.ImageFileEditor;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.datatransfer.DataFlavor;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The decoder UI inside a light IDE (IntelliJ test framework, the {@code test} task only), with a {@link FakeHeifBackend}
 * standing in for the system decoder: the banner of HEIC image editors, the probe that never runs on the EDT, "Check
 * Again", the once-per-session balloon and the diff hook.
 */
public class DecoderUiPlatformIntegrationTest extends BasePlatformTestCase {
  private static final HeifBackendStatus HEIF_MISSING =
    HeifBackendStatus.unavailable(Reason.WINDOWS_HEIF_EXTENSION_MISSING, "test: no WIC HEIF decoder");
  private final List<Notification> notifications = Collections.synchronizedList(new ArrayList<>());
  private boolean backendReplaced;
  private HeifBackend previousBackend;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    resetUi();
    Notifications listener = new Notifications() {
      @Override
      public void notify(@NotNull Notification notification) {
        notifications.add(notification);
      }
    };
    ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, listener);
    getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, listener);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      FileEditorManager manager = FileEditorManager.getInstance(getProject());
      for (VirtualFile file : manager.getOpenFiles()) manager.closeFile(file);
      if (backendReplaced) HeifBackends.replaceForTests(previousBackend);
      resetUi();
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  private static void resetUi() {
    DecoderStatus.resetForTests();
    DecoderPrompt.resetForTests();
    HeicViews.resetForTests();
    RemedyActions.resetForTests();
  }

  private FakeHeifBackend use(FakeHeifBackend backend) {
    HeifBackend previous = HeifBackends.replaceForTests(backend);
    if (!backendReplaced) {
      previousBackend = previous;
      backendReplaced = true;
    }
    return backend;
  }

  public void testBannerOfAHeicImageEditorWhileTheDecoderIsMissing() throws Exception {
    use(FakeHeifBackend.probed(HEIF_MISSING));
    VirtualFile file = heicFile("photo.heic");
    ImageFileEditor editor = createImageEditor(file);

    EditorNotificationPanel panel = banner(file, editor);
    assertNotNull("banner for a HEIC image editor", panel);
    assertTrue(panel.getText(), panel.getText().contains("HEIF Image Extension"));
    assertTrue(panel.getToolTipText(), panel.getToolTipText().contains("Microsoft Store"));
    HeifRemedy remedy = HeifRemedies.forStatus(HEIF_MISSING);
    assertNotNull(remedy);
    assertEquals("four actions: two links and More", 4, remedy.actions().size());
    assertNotNull(panel.findLabelByName(HeicBundle.message("remedy.action.open.store")));
    assertNotNull(panel.findLabelByName(HeicBundle.message("remedy.action.check.again")));
    assertNotNull(panel.findLabelByName(HeicBundle.message("remedy.action.more")));
    assertNull(panel.findLabelByName(HeicBundle.message("remedy.action.learn.more")));

    // Only image editors get it (e.g. not the text editor of another file).
    Function<? super FileEditor, ? extends JComponent> data = new HeicDecoderNotificationProvider().collectNotificationData(getProject(), file);
    assertNotNull(data);
    myFixture.configureByText("notes.txt", "text");
    TextEditor textEditor = (TextEditor) FileEditorManager.getInstance(getProject()).getSelectedEditor(myFixture.getFile().getVirtualFile());
    assertNotNull(textEditor);
    assertNull(data.apply(textEditor));
  }

  public void testBannersOfOtherReasons() throws Exception {
    VirtualFile file = heicFile("other.heic");
    ImageFileEditor editor = createImageEditor(file);
    HeifBackendStatus[] statuses = {
      HeifBackendStatus.unavailable(Reason.WINDOWS_HEVC_EXTENSION_MISSING, "test"),
      HeifBackendStatus.unavailable(Reason.LINUX_LIBHEIF_MISSING, "test").withInstallCommand("sudo apt install libheif1 libheif-plugin-libde265"),
      HeifBackendStatus.unavailable(Reason.LINUX_HEVC_PLUGIN_MISSING, "test"),
      HeifBackendStatus.unavailable(Reason.ERROR, "test"),
      HeifBackendStatus.unavailable(Reason.UNSUPPORTED_OS, "test"),
    };
    for (HeifBackendStatus status : statuses) {
      use(FakeHeifBackend.probed(status));
      EditorNotificationPanel panel = banner(file, editor);
      assertNotNull(status.toString(), panel);
      HeifRemedy remedy = HeifRemedies.forStatus(status);
      assertNotNull(remedy);
      assertTrue(panel.getText(), panel.getText().startsWith(HeicBundle.message(remedy.titleKey())));
      if (status.installCommand() != null) assertTrue(panel.getText(), panel.getText().endsWith(status.installCommand()));
      if (remedy.actions().size() <= HeicDecoderNotificationProvider.MAX_LINKS) {
        for (HeifRemedy.Action action : remedy.actions()) {
          assertNotNull(action.textKey(), panel.findLabelByName(HeicBundle.message(action.textKey())));
        }
      }
    }
  }

  /**
   * Every unavailable reason, with what the backends really report (the Windows backend's Store app page and winget
   * command, the Linux backend's package command and README section, or no command, as for a Flatpak IDE): the banner
   * shows the title (with the command where copying it is the remedy) and at most three links, the missing-decoder
   * balloon offers every action and "Don't Show Again", and where there is "Check Again", clicking it in the banner
   * finds a decoder that was installed meanwhile: the banner goes away and the success balloon appears.
   */
  public void testEveryReasonWithTheRemediesOfTheBackends() throws Exception {
    HeifBackendStatus[] statuses = {
      HeifBackendStatus.unavailable(Reason.WINDOWS_HEIF_EXTENSION_MISSING, "test")
        .withInstallUrl(WindowsCodecs.HEIF_STORE_APP_URL).withInstallCommand(WindowsCodecs.HEIF_WINGET_COMMAND),
      HeifBackendStatus.unavailable(Reason.WINDOWS_HEVC_EXTENSION_MISSING, "test").withInstallUrl(WindowsCodecs.HEVC_STORE_APP_URL),
      HeifBackendStatus.unavailable(Reason.LINUX_LIBHEIF_MISSING, "test")
        .withInstallCommand("sudo apt install libheif1 libheif-plugin-libde265").withInstallUrl(HeifRemedies.LINUX_HELP_URL),
      HeifBackendStatus.unavailable(Reason.LINUX_HEVC_PLUGIN_MISSING, "test")
        .withInstallCommand("sudo apt install libheif-plugin-libde265").withInstallUrl(HeifRemedies.LINUX_HELP_URL),
      HeifBackendStatus.unavailable(Reason.LINUX_LIBHEIF_MISSING, "test: Flatpak").withInstallUrl(HeifRemedies.LINUX_HELP_URL),
      HeifBackendStatus.unavailable(Reason.LINUX_HEVC_PLUGIN_MISSING, "test: Flatpak 25.08")
        .withInstallCommand("flatpak install flathub org.freedesktop.Platform.codecs-extra//25.08-extra")
        .withInstallUrl(HeifRemedies.LINUX_HELP_URL),
      HeifBackendStatus.unavailable(Reason.ERROR, "test"),
      HeifBackendStatus.unavailable(Reason.UNSUPPORTED_OS, "test"),
    };
    Set<Reason> covered = EnumSet.noneOf(Reason.class);
    int index = 0;
    for (HeifBackendStatus status : statuses) {
      resetUi();
      notifications.clear();
      covered.add(status.reason());
      HeifRemedy remedy = HeifRemedies.forStatus(status);
      assertNotNull(remedy);
      FakeHeifBackend backend = use(FakeHeifBackend.probed(status));
      backend.recheckResult = HeifBackendStatus.available("test: installed");
      VirtualFile file = heicFile("reason" + index++ + ".heic");
      ImageFileEditor editor = createImageEditor(file);

      EditorNotificationPanel panel = banner(file, editor);
      assertNotNull(status.toString(), panel);
      String title = HeicBundle.message(remedy.titleKey());
      List<HeifRemedy.Action> actions = remedy.actions();
      boolean commandFirst = actions.get(0).type() == HeifRemedy.ActionType.COPY_COMMAND;
      assertEquals(status.toString(), HeicDecoderNotificationProvider.text(remedy), panel.getText());
      assertTrue(panel.getText(), panel.getText().startsWith(title));
      if (commandFirst) {
        assertTrue(panel.getToolTipText(), panel.getToolTipText().contains(remedy.command()));
        if (!remedy.isHostCommand()) assertTrue(panel.getText(), panel.getText().endsWith(remedy.command())); // short enough
      }
      if (remedy.isHostCommand()) {
        assertTrue(panel.getText(), panel.getText().contains("On the host") && panel.getText().contains("\u2026"));
        assertFalse(panel.getToolTipText(), panel.getToolTipText().contains("distribution"));
      }
      int links = actions.size() <= HeicDecoderNotificationProvider.MAX_LINKS ? actions.size() : HeicDecoderNotificationProvider.MAX_LINKS - 1;
      for (int i = 0; i < actions.size(); i++) {
        HyperlinkLabel link = panel.findLabelByName(HeicBundle.message(actions.get(i).textKey()));
        if (i < links) assertNotNull(status + ": " + actions.get(i), link);
        else assertNull(status + ": " + actions.get(i) + " belongs under More", link);
      }
      assertEquals(status.toString(), links < actions.size(), panel.findLabelByName(HeicBundle.message("remedy.action.more")) != null);

      DecoderPrompt.decodeUnavailable(status, getProject());
      assertEquals(status.toString(), 1, notifications.size());
      Notification missing = notifications.get(0);
      List<String> balloonActions = new ArrayList<>();
      missing.getActions().forEach(action -> balloonActions.add(action.getTemplateText()));
      List<String> expected = new ArrayList<>();
      for (HeifRemedy.Action action : actions) expected.add(HeicBundle.message(action.textKey()));
      assertEquals(status.toString(), expected, balloonActions);
      if (remedy.command() != null) {
        assertTrue(missing.getContent(), missing.getContent().contains(StringUtil.escapeXmlEntities(remedy.command())));
      }

      if (!remedy.actions().contains(HeifRemedy.Action.checkAgain())) {
        assertEquals(Reason.UNSUPPORTED_OS, status.reason());
        continue;
      }
      notifications.clear();
      clickCheckAgain(file, editor);
      waitFor("the result of Check Again for " + status, () -> !notifications.isEmpty() && !isRechecking());
      assertEquals(1, backend.rechecks.get());
      assertEquals(HeicBundle.message("remedy.check.available.title"), notifications.get(0).getTitle());
      assertNull("no banner once the decoder is there: " + status, banner(file, editor));
    }
    assertEquals(EnumSet.allOf(Reason.class), covered);
  }

  public void testNoBannerWhenTheDecoderIsAvailableOrForOtherFiles() throws Exception {
    use(FakeHeifBackend.probed(HeifBackendStatus.available("test")));
    VirtualFile heic = heicFile("fine.heic");
    assertNull(new HeicDecoderNotificationProvider().collectNotificationData(getProject(), heic));

    use(FakeHeifBackend.probed(HEIF_MISSING));
    VirtualFile png = myFixture.getTempDirFixture().createFile("picture.png");
    assertNull(new HeicDecoderNotificationProvider().collectNotificationData(getProject(), png));
    assertNotNull(new HeicDecoderNotificationProvider().collectNotificationData(getProject(), heic));

    // Closing the banner hides it for this reason until the IDE restarts.
    HeicViews.hideBanners(Reason.WINDOWS_HEIF_EXTENSION_MISSING);
    assertNull(new HeicDecoderNotificationProvider().collectNotificationData(getProject(), heic));
  }

  /** The provider never probes itself: it starts a probe on a pooled thread and shows the banner once it is known. */
  public void testUnknownStatusIsProbedOffTheEdt() throws Exception {
    FakeHeifBackend backend = use(FakeHeifBackend.unprobed(HEIF_MISSING));
    VirtualFile file = heicFile("later.heic");
    assertNull(new HeicDecoderNotificationProvider().collectNotificationData(getProject(), file));
    HeifBackendStatus status = PlatformTestUtil.waitForFuture(DecoderStatus.status(), 10_000);
    assertEquals(HEIF_MISSING, status);
    assertEquals(1, backend.probes.get());
    assertNotNull(backend.probeThread);
    assertFalse("probed on " + backend.probeThread, backend.probeThread == edt());
    assertNotNull(new HeicDecoderNotificationProvider().collectNotificationData(getProject(), file));
    // A single probe, however often asked.
    assertEquals(HEIF_MISSING, PlatformTestUtil.waitForFuture(DecoderStatus.status(), 10_000));
    assertEquals(1, backend.probes.get());
  }

  public void testCheckAgainWhenTheDecoderBecameAvailable() throws Exception {
    FakeHeifBackend backend = use(FakeHeifBackend.probed(HEIF_MISSING));
    backend.recheckResult = HeifBackendStatus.available("test: installed");
    VirtualFile file = heicFile("now.heic");
    ImageFileEditor editor = createImageEditor(file);
    assertNotNull(banner(file, editor));

    clickCheckAgain(file, editor);
    waitFor("the result balloon", () -> !notifications.isEmpty());
    assertEquals(1, backend.rechecks.get());
    assertFalse(backend.probeThread == edt());
    Notification result = notifications.get(0);
    assertEquals(HeicBundle.message("remedy.check.available.title"), result.getTitle());
    assertEquals(NotificationType.INFORMATION, result.getType());
    assertNull("no banner any more", new HeicDecoderNotificationProvider().collectNotificationData(getProject(), file));
    // An image editor can be told to load its file again (it only loads on open or change).
    assertTrue(HeicViews.refresh(editor.getImageEditor()));
  }

  public void testCheckAgainWhenTheDecoderIsStillMissing() throws Exception {
    FakeHeifBackend backend = use(FakeHeifBackend.probed(HEIF_MISSING));
    backend.recheckResult = HeifBackendStatus.unavailable(Reason.WINDOWS_HEVC_EXTENSION_MISSING, "test: HEVC missing");
    VirtualFile file = heicFile("still.heic");
    ImageFileEditor editor = createImageEditor(file);

    clickCheckAgain(file, editor);
    waitFor("the result balloon", () -> !notifications.isEmpty());
    Notification result = notifications.get(0);
    assertEquals(HeicBundle.message("remedy.check.missing.title"), result.getTitle());
    assertEquals(NotificationType.WARNING, result.getType());
    assertTrue(result.getContent(), result.getContent().contains(HeicBundle.message(HeifRemedy.titleKey(Reason.WINDOWS_HEVC_EXTENSION_MISSING))));
    assertTrue("a Store package may need a restart: " + result.getContent(),
               result.getContent().contains(HeicBundle.message("remedy.check.missing.restart")));
    List<String> actions = new ArrayList<>();
    result.getActions().forEach(action -> actions.add(action.getTemplateText()));
    assertTrue(actions.toString(), actions.contains(HeicBundle.message("remedy.action.check.again")));
    assertTrue(actions.toString(), actions.contains(HeicBundle.message("remedy.action.open.store")));
    // The banner now names what is missing now.
    EditorNotificationPanel panel = banner(file, editor);
    assertNotNull(panel);
    assertTrue(panel.getText(), panel.getText().contains("HEVC Video Extensions"));

    // No restart hint where a re-check finds what was installed (Linux rescans the plugin directories).
    notifications.clear();
    DecoderPrompt.showStillMissing(HeifBackendStatus.unavailable(Reason.LINUX_HEVC_PLUGIN_MISSING, "test"), getProject());
    assertEquals(1, notifications.size());
    assertFalse(notifications.get(0).getContent(), notifications.get(0).getContent().contains(HeicBundle.message("remedy.check.missing.restart")));
  }

  public void testMissingBalloonOncePerSession() {
    use(FakeHeifBackend.probed(HEIF_MISSING));
    DecoderPrompt.decodeUnavailable(HEIF_MISSING, getProject());
    DecoderPrompt.decodeUnavailable(HEIF_MISSING, getProject());
    DecoderPrompt.decodeUnavailable(HeifBackendStatus.unavailable(Reason.WINDOWS_HEVC_EXTENSION_MISSING, "test"), null);
    assertEquals(1, notifications.size());
    Notification missing = notifications.get(0);
    assertEquals(HeicBundle.message(HeifRemedy.titleKey(Reason.WINDOWS_HEIF_EXTENSION_MISSING)), missing.getTitle());
    List<String> actions = new ArrayList<>();
    missing.getActions().forEach(action -> actions.add(action.getTemplateText()));
    List<String> expected = new ArrayList<>();
    for (HeifRemedy.Action action : HeifRemedies.forStatus(HEIF_MISSING).actions()) expected.add(HeicBundle.message(action.textKey()));
    assertEquals("the remedy's actions", expected, actions);
    assertEquals(HeicBundle.message("remedy.action.open.store"), actions.get(0));
  }

  /** The platform collects banners by itself only for text editors: opening a HEIC file asks for its banner. */
  public void testOpeningAHeicFileAsksForItsBannerWhileTheDecoderIsMissing() throws Exception {
    List<VirtualFile> updated = Collections.synchronizedList(new ArrayList<>());
    ServiceContainerUtil.replaceService(getProject(), EditorNotifications.class, new EditorNotifications() {
      @Override
      public void updateNotifications(@NotNull VirtualFile file) {
        updated.add(file);
      }

      @Override
      @SuppressWarnings("deprecation") // abstract in 2024.1 - 2026.2, deprecated since removeNotificationsForProvider exists
      public void updateNotifications(@NotNull EditorNotificationProvider provider) {
      }

      @Override
      public void updateAllNotifications() {
      }
    }, getTestRootDisposable());
    FileEditorManager manager = FileEditorManager.getInstance(getProject());
    VirtualFile heic = heicFile("opened.heic");
    VirtualFile text = myFixture.getTempDirFixture().createFile("opened.txt", "text");

    use(FakeHeifBackend.probed(HeifBackendStatus.available("test")));
    new HeicFileOpenedListener().fileOpened(manager, heic);
    assertEquals("nothing while the decoder is available", List.of(), updated);

    use(FakeHeifBackend.probed(HEIF_MISSING));
    new HeicFileOpenedListener().fileOpened(manager, text);
    assertEquals(List.of(), updated);
    new HeicFileOpenedListener().fileOpened(manager, heic);
    assertEquals(List.of(heic), updated);

    // Not probed yet: probes off the EDT instead (a missing decoder then updates every banner).
    FakeHeifBackend unprobed = use(FakeHeifBackend.unprobed(HEIF_MISSING));
    DecoderStatus.resetForTests();
    new HeicFileOpenedListener().fileOpened(manager, heic);
    PlatformTestUtil.waitForFuture(DecoderStatus.status(), 10_000);
    assertEquals(1, unprobed.probes.get());
    assertFalse(unprobed.probeThread == edt());
  }

  public void testDiffOfHeicFilesExplainsTheMissingDecoder() throws Exception {
    use(FakeHeifBackend.probed(HEIF_MISSING));
    VirtualFile before = heicFile("before.heic");
    VirtualFile after = heicFile("after.heic");
    DiffContentFactory contents = DiffContentFactory.getInstance();
    SimpleDiffRequest heic = new SimpleDiffRequest("HEIC", contents.create(getProject(), before), contents.create(getProject(), after), "a", "b");
    assertTrue(HeicDiffExtension.showsHeicFile(heic));
    SimpleDiffRequest text = new SimpleDiffRequest("text", contents.create("a"), contents.create("b"), "a", "b");
    assertFalse(HeicDiffExtension.showsHeicFile(text));
  }

  /** Copy Command puts the command on the clipboard; only "Check Again" probes again. */
  public void testCopyCommand() throws Exception {
    HeifBackendStatus libheifMissing = HeifBackendStatus.unavailable(Reason.LINUX_LIBHEIF_MISSING, "test")
      .withInstallCommand("sudo zypper install libheif1");
    FakeHeifBackend backend = use(FakeHeifBackend.probed(libheifMissing));
    HeifRemedy remedy = HeifRemedies.forStatus(libheifMissing);
    assertNotNull(remedy);
    RemedyActions.perform(remedy.actions().get(0), getProject(), null);
    assertEquals("sudo zypper install libheif1", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals(0, backend.rechecks.get());
    assertEquals(0, notifications.size());
  }

  /** The removal of this plugin's banners before unloading reaches the platform under either of its names. */
  public void testBannersCanBeRemovedBeforeUnloading() throws Exception {
    List<EditorNotificationProvider> removed = new ArrayList<>();
    EditorNotifications recorder = new EditorNotifications() {
      @Override
      public void updateNotifications(@NotNull VirtualFile file) {
      }

      @Override
      @SuppressWarnings("deprecation") // abstract in 2024.1 - 2026.2, deprecated since removeNotificationsForProvider exists
      public void updateNotifications(@NotNull EditorNotificationProvider provider) {
        removed.add(provider);
      }

      @Override
      public void updateAllNotifications() {
      }
    };
    HeicDecoderNotificationProvider provider = new HeicDecoderNotificationProvider();
    HeicViews.removePanels(recorder, provider);
    assertEquals(List.of(provider), removed);
  }

  public void testNothingAfterShutDown() throws Exception {
    use(FakeHeifBackend.probed(HEIF_MISSING));
    VirtualFile file = heicFile("unloading.heic");
    ImageFileEditor editor = createImageEditor(file);
    DecoderPrompt.decodeUnavailable(HEIF_MISSING, getProject());
    assertEquals(1, notifications.size());
    DecoderUi.shutDown();
    assertTrue("balloons expire before the plugin is unloaded", notifications.get(0).isExpired());
    assertNull(banner(file, editor));
    DecoderStatus.recheck(getProject());
    assertFalse("no re-check after the shutdown", isRechecking());
    // The backend is released right after: nothing may look it up (and create a new one) any more.
    HeifBackends.replaceForTests(null);
    assertNull(DecoderStatus.cached());
    assertFalse(PlatformTestUtil.waitForFuture(DecoderStatus.status(), 10_000).isAvailable());
    assertNull(new HeicDecoderNotificationProvider().collectNotificationData(getProject(), file));
    assertNull("no backend was created", HeifBackends.replaceForTests(null));
  }

  /** "Check Again" clicked while a re-check runs is answered by that re-check: one probe, one balloon. */
  public void testCheckAgainWhileARecheckRuns() throws Exception {
    FakeHeifBackend backend = use(FakeHeifBackend.probed(HEIF_MISSING));
    backend.recheckResult = HeifBackendStatus.unavailable(Reason.WINDOWS_HEVC_EXTENSION_MISSING, "test: HEVC missing");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    backend.recheckEntered = entered;
    backend.recheckRelease = release;
    RemedyActions.perform(HeifRemedy.Action.checkAgain(), getProject(), null);
    assertTrue("the re-check probes", entered.await(10, TimeUnit.SECONDS));

    RemedyActions.perform(HeifRemedy.Action.checkAgain(), getProject(), null);
    release.countDown();
    waitFor("the answer to Check Again", () -> backend.rechecks.get() == 1 && !isRechecking() && !notifications.isEmpty());
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    assertEquals(1, backend.rechecks.get());
    assertEquals(1, notifications.size());
    assertEquals(HeicBundle.message("remedy.check.missing.title"), notifications.get(0).getTitle());
  }

  /**
   * A banner function collected before the shutdown (the platform applies it later, in a separate EDT step) creates
   * nothing after it, nor after the user closed the banner meanwhile.
   */
  public void testBannerCollectedBeforeTheShutdownIsNotCreatedAfterIt() throws Exception {
    use(FakeHeifBackend.probed(HEIF_MISSING));
    VirtualFile file = heicFile("late.heic");
    ImageFileEditor editor = createImageEditor(file);
    HeicDecoderNotificationProvider provider = new HeicDecoderNotificationProvider();

    Function<? super FileEditor, ? extends JComponent> closed = provider.collectNotificationData(getProject(), file);
    assertNotNull(closed);
    HeicViews.hideBanners(Reason.WINDOWS_HEIF_EXTENSION_MISSING);
    assertNull("closed by the user meanwhile", closed.apply(editor));
    HeicViews.resetForTests();

    Function<? super FileEditor, ? extends JComponent> data = provider.collectNotificationData(getProject(), file);
    assertNotNull(data);
    DecoderUi.shutDown();
    assertNull("no panel after beforePluginUnload", data.apply(editor));
  }

  /**
   * The banner's "More" popup (a window created by plugin code) is closed before the plugin is unloaded, and when the
   * decoder becomes available; after the shutdown no popup is shown.
   */
  public void testMorePopupIsClosedWithTheBanners() throws Exception {
    use(FakeHeifBackend.probed(HEIF_MISSING));
    VirtualFile file = heicFile("more.heic");
    ImageFileEditor editor = createImageEditor(file);
    EditorNotificationPanel panel = banner(file, editor);
    assertNotNull(panel);
    HyperlinkLabel more = panel.findLabelByName(HeicBundle.message("remedy.action.more"));
    assertNotNull(more);

    JBPopup first = clickMore(more);
    assertSame(first, RemedyActions.trackedPopupsForTests()[1]);
    JBPopup second = clickMore(more);
    assertTrue("a second popup replaces the first", first.isDisposed());
    RemedyActions.closePopups(); // the decoder became available: the banners go away
    assertTrue(second.isDisposed());
    assertNull(RemedyActions.trackedPopupsForTests()[1]);

    JBPopup third = clickMore(more);
    DecoderUi.shutDown();
    assertTrue("closed before the plugin is unloaded", third.isDisposed());
    JBPopup late = JBPopupFactory.getInstance().createPopupChooserBuilder(List.of("a", "b")).createPopup();
    assertFalse("nothing is shown while the plugin is unloaded", RemedyActions.popupShown(late));
    assertTrue(late.isDisposed());
  }

  /**
   * On shutdown the "copied" confirmation is hidden without animation and disposed at once; the plugin references it
   * only weakly.
   */
  public void testCopiedConfirmationIsClosedAtOnceAndNotKept() throws Exception {
    List<String> calls = Collections.synchronizedList(new ArrayList<>());
    RemedyActions.trackCopiedBalloonForTests(recordingBalloon(calls));
    DecoderUi.shutDown();
    assertEquals(List.of("hideImmediately", "dispose"), calls);
    assertNull(RemedyActions.trackedPopupsForTests()[0]);

    RemedyActions.resetForTests();
    Balloon kept = recordingBalloon(new ArrayList<>());
    RemedyActions.trackCopiedBalloonForTests(kept);
    assertSame(kept, RemedyActions.trackedPopupsForTests()[0]);
    WeakReference<Balloon> reference = new WeakReference<>(kept);
    kept = null;
    for (int i = 0; i < 100 && reference.get() != null; i++) {
      System.gc();
      Thread.sleep(10);
    }
    assertNull("referenced weakly", reference.get());
    assertNull(RemedyActions.trackedPopupsForTests()[0]);
  }

  /**
   * The balloons of a closed project, which may stay in the plugin's list of notifications to expire, do not reference
   * the project.
   */
  public void testClosedProjectIsNotKeptByTheBalloons() throws Exception {
    Path directory = Files.createTempDirectory("heic-closed-project");
    Disposable parent = Disposer.newDisposable();
    try {
      Project other = PlatformTestUtil.loadAndOpenProject(directory, parent);
      Notification balloon;
      try {
        assertNotSame(getProject(), other);
        DecoderPrompt.decodeUnavailable(HEIF_MISSING, other); // the diff of project "other"
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        List<?> shown = (List<?>) DecoderPrompt.shownNotificationsForTests();
        assertEquals(1, shown.size());
        balloon = (Notification) shown.get(0);
        assertEquals(4, balloon.getActions().size()); // the four actions of the remedy
      }
      finally {
        PlatformTestUtil.forceCloseProjectWithoutSaving(other);
      }
      assertTrue(other.isDisposed());
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
      LeakHunter.checkLeak(balloon, Project.class, project -> project == other);
      LeakHunter.checkLeak(DecoderPrompt.shownNotificationsForTests(), Project.class, project -> project == other);
    }
    finally {
      Disposer.dispose(parent);
    }
  }

  // ---------------------------------------------------------------------------------------------------------------------

  /** Clicks the banner's "More" link and returns the popup it shows (intercepted: a light IDE is headless). */
  private static JBPopup clickMore(HyperlinkLabel more) {
    List<JBPopup> shown = new ArrayList<>();
    UiInterceptors.register(new UiInterceptors.UiInterceptor<>(JBPopup.class) {
      @Override
      protected void doIntercept(@NotNull JBPopup popup) {
        shown.add(popup);
      }
    });
    more.doClick();
    assertEquals(1, shown.size());
    return shown.get(0);
  }

  /** A {@link Balloon} that records the calls of the plugin (hideImmediately, hide, dispose, ...). */
  private static Balloon recordingBalloon(List<String> calls) {
    return (Balloon) Proxy.newProxyInstance(Balloon.class.getClassLoader(), new Class<?>[]{Balloon.class}, (proxy, method, args) -> {
      switch (method.getName()) {
        case "hashCode": return System.identityHashCode(proxy);
        case "equals": return proxy == args[0];
        case "toString": return "recording balloon";
        default:
          calls.add(method.getName());
          return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
      }
    });
  }

  private static boolean isRechecking() {
    return DecoderStatus.isRecheckingForTests();
  }

  private void clickCheckAgain(VirtualFile file, ImageFileEditor editor) {
    EditorNotificationPanel panel = banner(file, editor);
    assertNotNull(panel);
    HyperlinkLabel checkAgain = panel.findLabelByName(HeicBundle.message("remedy.action.check.again"));
    assertNotNull(checkAgain);
    checkAgain.doClick();
  }

  private void waitFor(String what, java.util.function.BooleanSupplier condition) {
    PlatformTestUtil.waitWithEventsDispatching("Timed out waiting for " + what, condition, 10);
  }

  private static Thread edt() {
    Thread[] edt = new Thread[1];
    ApplicationManager.getApplication().invokeAndWait(() -> edt[0] = Thread.currentThread());
    return edt[0];
  }

  private VirtualFile heicFile(String name) throws Exception {
    VirtualFile file = myFixture.getTempDirFixture().createFile(name);
    byte[] content = Fixtures.bytes("exif6_apple.heic");
    WriteAction.runAndWait(() -> file.setBinaryContent(content));
    return file;
  }

  /**
   * The image editor the IDE would open for {@code file}, created through its provider (the light test framework's
   * editor manager opens text editors only).
   */
  private ImageFileEditor createImageEditor(VirtualFile file) {
    List<FileEditorProvider> providers = FileEditorProviderManager.getInstance().getProviderList(getProject(), file);
    for (FileEditorProvider provider : providers) {
      FileEditor editor = provider.createEditor(getProject(), file);
      Disposer.register(getTestRootDisposable(), editor);
      if (editor instanceof ImageFileEditor) return (ImageFileEditor) editor;
    }
    fail("no image editor for " + file + ": " + providers);
    return null;
  }

  /** The banner the provider creates for {@code editor} (EDT), or {@code null}. */
  private EditorNotificationPanel banner(VirtualFile file, FileEditor editor) {
    Function<? super FileEditor, ? extends JComponent> data = new HeicDecoderNotificationProvider().collectNotificationData(getProject(), file);
    if (data == null) return null;
    JComponent component = data.apply(editor);
    if (component != null) Disposer.register(getTestRootDisposable(), () -> component.removeAll());
    return (EditorNotificationPanel) component;
  }
}
