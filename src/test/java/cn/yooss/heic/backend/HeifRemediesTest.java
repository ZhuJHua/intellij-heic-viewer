package cn.yooss.heic.backend;

import cn.yooss.heic.backend.HeifBackendStatus.Reason;
import cn.yooss.heic.backend.HeifRemedy.Action;
import cn.yooss.heic.backend.HeifRemedy.ActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remedy of every {@link Reason}: texts in English and Chinese, only {@code https:} / {@code ms-windows-store:} URLs,
 * non-empty single-line commands, and how a backend's install URL and command replace the defaults. Pure Java (the
 * bundles are read as properties files), so it runs on JDK 17 as well.
 */
class HeifRemediesTest {
  private static final List<String> BUNDLES = List.of("messages/HeicBundle.properties", "messages/HeicBundle_zh_CN.properties");

  @ParameterizedTest
  @EnumSource(Reason.class)
  void everyReasonHasARemedyWithTextsInEveryBundle(Reason reason) throws IOException {
    HeifRemedy remedy = HeifRemedies.forReason(reason);
    assertSame(reason, remedy.reason());
    assertEquals("remedy.title." + reason.name(), remedy.titleKey());
    assertEquals(reason.bundleKey(), remedy.explanationKey());
    assertFalse(remedy.actions().isEmpty(), reason.name());
    for (String bundle : BUNDLES) {
      Properties texts = properties(bundle);
      assertText(texts, bundle, remedy.titleKey());
      assertText(texts, bundle, remedy.explanationKey());
      for (Action action : remedy.actions()) assertText(texts, bundle, action.textKey());
    }
  }

  @ParameterizedTest
  @EnumSource(Reason.class)
  void urlsAreHttpsOrMicrosoftStoreAndCommandsAreSingleLines(Reason reason) {
    HeifRemedy remedy = HeifRemedies.forReason(reason);
    for (Action action : remedy.actions()) {
      String target = action.target();
      switch (action.type()) {
        case OPEN_URL:
        case LEARN_MORE:
          assertNotNull(target, action.toString());
          String scheme = URI.create(target).getScheme().toLowerCase(Locale.ROOT);
          assertTrue(scheme.equals("https") || scheme.equals("ms-windows-store"), action.toString());
          if (scheme.equals("https")) assertNotNull(URI.create(target).getHost(), action.toString());
          break;
        case COPY_COMMAND:
          assertNotNull(target, action.toString());
          assertFalse(target.trim().isEmpty(), action.toString());
          assertFalse(target.contains("\n") || target.contains("\r"), action.toString());
          assertEquals(remedy.command(), target, "the copied command is the one shown");
          break;
        case OPEN_SETTINGS:
          assertNotNull(target, action.toString());
          assertFalse(target.trim().isEmpty(), action.toString());
          break;
        case CHECK_AGAIN:
          assertNull(target, action.toString());
          break;
      }
    }
    if (remedy.command() != null) assertTrue(HeifRemedies.isAllowedCommand(remedy.command()), remedy.toString());
  }

  /** What each kind of reason offers: installable ones a way to install and Check Again; errors Check Again. */
  @ParameterizedTest
  @EnumSource(Reason.class)
  void actionsFitTheReason(Reason reason) {
    HeifRemedy remedy = HeifRemedies.forReason(reason);
    Set<ActionType> types = new TreeSet<>();
    for (Action action : remedy.actions()) types.add(action.type());
    assertEquals(remedy.actions().size(), new TreeSet<>(remedy.actions().stream().map(Action::toString).toList()).size(),
                 "no duplicates: " + remedy);
    if (reason.isUserInstallable()) {
      assertTrue(types.contains(ActionType.CHECK_AGAIN), remedy.toString());
      ActionType first = remedy.actions().get(0).type();
      assertTrue(first == ActionType.OPEN_URL || first == ActionType.COPY_COMMAND, "installing comes first: " + remedy);
      assertTrue(types.contains(ActionType.LEARN_MORE), remedy.toString());
    }
    if (reason == Reason.ERROR) assertTrue(types.contains(ActionType.CHECK_AGAIN), remedy.toString());
    if (reason == Reason.UNSUPPORTED_OS || reason == Reason.NOT_IMPLEMENTED) {
      assertFalse(types.contains(ActionType.CHECK_AGAIN), "nothing to install: " + remedy);
    }
  }

  @Test
  void windowsRemediesOpenTheMicrosoftStoreFirst() {
    for (Reason reason : List.of(Reason.WINDOWS_HEIF_EXTENSION_MISSING, Reason.WINDOWS_HEVC_EXTENSION_MISSING)) {
      HeifRemedy remedy = HeifRemedies.forReason(reason);
      Action store = remedy.actions().get(0);
      assertEquals(ActionType.OPEN_URL, store.type());
      assertEquals("remedy.action.open.store", store.textKey());
      assertTrue(store.target().startsWith("ms-windows-store://pdp/?ProductId="), store.target());
      Action web = remedy.actions().get(2);
      assertEquals("remedy.action.open.store.web", web.textKey());
      assertTrue(web.target().startsWith("https://apps.microsoft.com/"), web.target());
      assertNull(remedy.command());
    }
    assertEquals(HeifRemedies.HEIF_EXTENSION_STORE_URL,
                 HeifRemedies.forReason(Reason.WINDOWS_HEIF_EXTENSION_MISSING).actions().get(0).target());
    assertEquals(HeifRemedies.HEVC_EXTENSION_STORE_URL,
                 HeifRemedies.forReason(Reason.WINDOWS_HEVC_EXTENSION_MISSING).actions().get(0).target());
  }

  @Test
  void linuxRemediesCopyTheCommandFirst() {
    for (Reason reason : List.of(Reason.LINUX_LIBHEIF_MISSING, Reason.LINUX_HEVC_PLUGIN_MISSING)) {
      HeifRemedy remedy = HeifRemedies.forReason(reason);
      Action copy = remedy.actions().get(0);
      assertEquals(ActionType.COPY_COMMAND, copy.type());
      assertEquals(remedy.command(), copy.target());
      assertEquals(ActionType.CHECK_AGAIN, remedy.actions().get(1).type());
    }
    assertEquals(HeifRemedies.LIBHEIF_COMMAND, HeifRemedies.forReason(Reason.LINUX_LIBHEIF_MISSING).command());
    assertEquals(HeifRemedies.HEVC_PLUGIN_COMMAND, HeifRemedies.forReason(Reason.LINUX_HEVC_PLUGIN_MISSING).command());
  }

  @Test
  void availableStatusHasNoRemedy() {
    assertNull(HeifRemedies.forStatus(HeifBackendStatus.available("ok")));
  }

  /** The backend knows the details of the running system: its URL and command replace the defaults. */
  @Test
  void backendUrlAndCommandReplaceTheDefaults() {
    HeifRemedy fedora = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.LINUX_LIBHEIF_MISSING, "test")
                                                 .withInstallCommand("sudo dnf install libheif-freeworld"));
    assertNotNull(fedora);
    assertEquals("sudo dnf install libheif-freeworld", fedora.command());
    assertEquals("sudo dnf install libheif-freeworld", fedora.action(ActionType.COPY_COMMAND).target());

    HeifRemedy docs = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.LINUX_HEVC_PLUGIN_MISSING, "test")
                                               .withInstallUrl("https://example.org/libheif"));
    assertNotNull(docs);
    assertEquals(HeifRemedies.HEVC_PLUGIN_COMMAND, docs.command());
    assertTrue(docs.actions().contains(Action.openUrl("remedy.action.open.install.page", "https://example.org/libheif")), docs.toString());

    String freeHevc = "ms-windows-store://pdp/?ProductId=9N4WGH0Z6VHQ";
    HeifRemedy windows = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.WINDOWS_HEVC_EXTENSION_MISSING, "test")
                                                  .withInstallUrl(freeHevc)
                                                  .withInstallCommand("winget install --id 9N4WGH0Z6VHQ --source msstore"));
    assertNotNull(windows);
    assertEquals(Action.openUrl("remedy.action.open.store", freeHevc), windows.actions().get(0));
    assertEquals(ActionType.COPY_COMMAND, windows.actions().get(1).type());
    assertEquals("winget install --id 9N4WGH0Z6VHQ --source msstore", windows.command());
  }

  /** A backend can never make the IDE open another URL scheme or copy a multi-line command. */
  @Test
  void disallowedBackendValuesAreIgnored() {
    HeifRemedy remedy = HeifRemedies.forStatus(HeifBackendStatus.unavailable(Reason.WINDOWS_HEIF_EXTENSION_MISSING, "test")
                                                 .withInstallUrl("file:///C:/Windows/System32/calc.exe")
                                                 .withInstallCommand("echo a\nrm -rf ~"));
    assertNotNull(remedy);
    assertEquals(HeifRemedies.forReason(Reason.WINDOWS_HEIF_EXTENSION_MISSING), remedy);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://apps.microsoft.com/detail/9pmmsr1cgpwg", "ms-windows-store://pdp/?ProductId=9PMMSR1CGPWG",
      "HTTPS://github.com/ZhuJHua/intellij-heic-viewer#requirements", "ms-windows-store:search?query=heif"})
  void allowedUrls(String url) {
    assertTrue(HeifRemedies.isAllowedUrl(url), url);
    assertEquals(url, Action.openUrl("remedy.action.open.store", url).target());
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://example.org", "file:///etc/passwd", "javascript:alert(1)", "ms-settings:appsfeatures",
      "https://", "https://user@example.org/", "//example.org", "example.org", " https://example.org", "ms-windows-store:",
      "https://exa mple.org"})
  void refusedUrls(String url) {
    assertFalse(HeifRemedies.isAllowedUrl(url), url);
    assertThrows(IllegalArgumentException.class, () -> Action.openUrl("remedy.action.open.store", url));
    assertThrows(IllegalArgumentException.class, () -> Action.learnMore(url));
  }

  @Test
  void commands() {
    assertTrue(HeifRemedies.isAllowedCommand("sudo apt install libheif1"));
    assertFalse(HeifRemedies.isAllowedCommand(null));
    assertFalse(HeifRemedies.isAllowedCommand(" "));
    assertFalse(HeifRemedies.isAllowedCommand("a\nb"));
    assertFalse(HeifRemedies.isAllowedCommand("a\tb"));
    assertFalse(HeifRemedies.isAllowedCommand("x".repeat(501)));
    assertThrows(IllegalArgumentException.class, () -> Action.copyCommand("a\nb"));
    assertThrows(IllegalArgumentException.class, () -> Action.copyCommand(""));
  }

  @Test
  void actionValueSemantics() {
    assertEquals(Action.checkAgain(), Action.checkAgain());
    assertEquals(Action.checkAgain().hashCode(), Action.checkAgain().hashCode());
    assertEquals("CHECK_AGAIN(remedy.action.check.again)", Action.checkAgain().toString());
    assertEquals(Action.openSettings("advanced.settings"), Action.openSettings("advanced.settings"));
    assertEquals(HeifRemedies.forReason(Reason.ERROR), HeifRemedies.forReason(Reason.ERROR));
    assertEquals(HeifRemedies.forReason(Reason.ERROR).hashCode(), HeifRemedies.forReason(Reason.ERROR).hashCode());
    assertThrows(UnsupportedOperationException.class, () -> HeifRemedies.forReason(Reason.ERROR).actions().clear());
    assertTrue(ActionType.OPEN_URL.startsInstallation());
    assertTrue(ActionType.COPY_COMMAND.startsInstallation());
    assertFalse(ActionType.CHECK_AGAIN.startsInstallation());
    assertFalse(ActionType.LEARN_MORE.startsInstallation());
  }

  /** Every remedy.* text exists in both bundles; MessageFormat patterns (with {0}) have no stray single quotes. */
  @Test
  void remedyTextsAreCompleteAndValidMessageFormats() throws IOException {
    Properties english = properties(BUNDLES.get(0));
    Properties chinese = properties(BUNDLES.get(1));
    List<String> remedyKeys = new ArrayList<>();
    for (String key : english.stringPropertyNames()) if (key.startsWith("remedy.")) remedyKeys.add(key);
    assertTrue(remedyKeys.size() > 20, remedyKeys.toString());
    for (Reason reason : Reason.values()) assertTrue(remedyKeys.contains(HeifRemedy.titleKey(reason)), reason.name());
    Pattern parameter = Pattern.compile("\\{(\\d)}");
    for (String key : remedyKeys) {
      String en = english.getProperty(key);
      String zh = chinese.getProperty(key);
      assertNotNull(zh, "zh_CN: " + key);
      assertFalse(zh.trim().isEmpty(), "zh_CN: " + key);
      if (!parameter.matcher(en).find()) continue;
      assertEquals(parameters(parameter, en), parameters(parameter, zh), key);
      for (String text : List.of(en, zh)) {
        assertFalse(text.replace("''", "").contains("'"), key + ": single quote in a MessageFormat pattern: " + text);
        assertEquals(text.replace("{0}", "A").replace("{1}", "B"), MessageFormat.format(text, "A", "B"), key);
      }
    }
  }

  private static Set<String> parameters(Pattern parameter, String text) {
    Set<String> found = new TreeSet<>();
    Matcher matcher = parameter.matcher(text);
    while (matcher.find()) found.add(matcher.group());
    return found;
  }

  private static void assertText(Properties texts, String bundle, String key) {
    String text = texts.getProperty(key);
    assertNotNull(text, bundle + ": " + key);
    assertFalse(text.trim().isEmpty(), bundle + ": " + key);
  }

  private static Properties properties(String resource) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = HeifRemediesTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, resource);
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return properties;
  }
}
