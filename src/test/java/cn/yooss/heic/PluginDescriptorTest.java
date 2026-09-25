package cn.yooss.heic;

import cn.yooss.heic.backend.HeifBackendStatus;
import cn.yooss.heic.backend.HeifRemedy;
import cn.yooss.heic.ui.DecoderPrompt;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the plugin descriptor, the Java constants and the message bundles consistent: a mismatch would not fail the
 * build, but silently break dynamic loading (plugin id), settings (ids, texts) or loading on some operating systems.
 */
class PluginDescriptorTest {
  @Test
  void pluginIdMatchesTheConstantUsedByTheDynamicPluginListener() throws Exception {
    Document plugin = parse("META-INF/plugin.xml");
    assertEquals(HeicSupport.PLUGIN_ID, text(plugin, "id"));
    assertEquals("cn.yooss.heic-viewer", HeicSupport.PLUGIN_ID);
    assertEquals("HEIC Viewer", text(plugin, "name"));
  }

  /**
   * Everything is declared in plugin.xml and loads on every OS (the backend decides at runtime whether a system decoder
   * is available). An OS dependency, even an optional one, makes IntelliJ 2024.1-2025.1 refuse the plugin on the other
   * systems, and an optional config file of an OS module that does not exist in the IDE is silently skipped.
   */
  @Test
  void noOsDependencyAndNoOptionalConfigFiles() throws Exception {
    Document plugin = parse("META-INF/plugin.xml");
    List<String> dependencies = new ArrayList<>();
    for (Element depends : elements(plugin, "depends")) {
      dependencies.add(depends.getTextContent().trim());
      assertEquals("", depends.getAttribute("optional"), "no optional dependencies: " + depends.getTextContent());
      assertEquals("", depends.getAttribute("config-file"), "no config files: " + depends.getTextContent());
    }
    assertEquals(List.of("com.intellij.modules.platform", "com.intellij.platform.images"), dependencies);
    assertEquals(1, plugin.getElementsByTagName("extensions").getLength());
    assertEquals(1, plugin.getElementsByTagName("applicationListeners").getLength());
    assertNull(PluginDescriptorTest.class.getClassLoader().getResource("META-INF/heic-viewer-macos.xml"),
               "the former macOS-only config file must be gone");
  }

  @Test
  void fileTypeExtensionsMatchTheReaderSuffixes() throws Exception {
    List<Element> fileTypes = elements(parse("META-INF/plugin.xml"), "fileType");
    assertEquals(1, fileTypes.size());
    assertEquals("Image", fileTypes.get(0).getAttribute("name"));
    assertEquals("", fileTypes.get(0).getAttribute("implementationClass"), "merged into the platform's Image file type");
    assertEquals(String.join(";", HeicImageReaderSpi.SUFFIXES), fileTypes.get(0).getAttribute("extensions"));
  }

  @Test
  void advancedSettingsMatchTheConstantsAndHaveTextsInEveryBundle() throws Exception {
    List<Element> settings = elements(parse("META-INF/plugin.xml"), "advancedSetting");
    Set<String> ids = new TreeSet<>();
    for (Element setting : settings) {
      ids.add(setting.getAttribute("id"));
      assertEquals("messages.HeicBundle", setting.getAttribute("bundle"));
      assertEquals("group.advanced.settings.heic", setting.getAttribute("groupKey"));
    }
    assertEquals(new TreeSet<>(Set.of(HeicSettings.MAX_MEGAPIXELS, HeicSettings.PROJECT_VIEW_THUMBNAILS,
                                      HeicSettings.LIBHEIF_PATH)), ids);

    for (String bundle : List.of("messages/HeicBundle.properties", "messages/HeicBundle_zh_CN.properties")) {
      Properties texts = properties(bundle);
      assertEquals("HEIC Viewer", texts.getProperty("group.advanced.settings.heic"), bundle);
      for (String id : ids) {
        assertTrue(id.startsWith("heic.viewer."), id);
        assertNotNull(texts.getProperty("advanced.setting." + id), bundle + ": " + id);
        assertNotNull(texts.getProperty("advanced.setting." + id + ".description"), bundle + ": " + id);
      }
      assertEquals(properties("messages/HeicBundle.properties").stringPropertyNames(), texts.stringPropertyNames(), bundle);
    }
  }

  /**
   * Every backend status reason and every text of the decoder UI exists in English and Chinese (the remedies of each
   * reason are checked by HeifRemediesTest).
   */
  @Test
  void decoderStatusTextsExistInEveryBundle() throws Exception {
    List<String> keys = new ArrayList<>();
    for (HeifBackendStatus.Reason reason : HeifBackendStatus.Reason.values()) {
      keys.add(reason.bundleKey());
      keys.add(HeifRemedy.titleKey(reason));
    }
    keys.addAll(List.of("notification.group.heic", "remedy.command.label", "remedy.banner.command", "remedy.command.copied",
                        "remedy.action.open.store", "remedy.action.open.store.web", "remedy.action.open.install.page",
                        "remedy.action.copy.command", "remedy.action.open.settings", "remedy.action.check.again",
                        "remedy.action.learn.more", "remedy.action.report", "remedy.action.dont.show.again", "remedy.action.more",
                        "remedy.check.available.title", "remedy.check.available.content", "remedy.check.missing.title"));
    for (String bundle : List.of("messages/HeicBundle.properties", "messages/HeicBundle_zh_CN.properties")) {
      Properties texts = properties(bundle);
      for (String key : keys) {
        String text = texts.getProperty(key);
        assertNotNull(text, bundle + ": " + key);
        assertFalse(text.trim().isEmpty(), bundle + ": " + key);
      }
    }
    List<Element> groups = elements(parse("META-INF/plugin.xml"), "notificationGroup");
    assertEquals(1, groups.size());
    assertEquals(DecoderPrompt.NOTIFICATION_GROUP, groups.get(0).getAttribute("id"));
    assertEquals("notification.group.heic", groups.get(0).getAttribute("key"));
    assertEquals("messages.HeicBundle", groups.get(0).getAttribute("bundle"));
    assertEquals("BALLOON", groups.get(0).getAttribute("displayType"), "not sticky: once per session, not in the way");
  }

  /**
   * The decoder UI: the editor banner (and the listener that asks for it when a HEIC file is opened), the diff hook and
   * the re-check on activation, all dynamic extension points and declarative listeners.
   */
  @Test
  void decoderUiIsRegistered() throws Exception {
    Document plugin = parse("META-INF/plugin.xml");
    List<Element> banners = elements(plugin, "editorNotificationProvider");
    assertEquals(1, banners.size());
    assertEquals("cn.yooss.heic.ui.HeicDecoderNotificationProvider", banners.get(0).getAttribute("implementation"));
    List<Element> diff = elements(plugin, "diff.DiffExtension");
    assertEquals(1, diff.size());
    assertEquals("cn.yooss.heic.ui.HeicDiffExtension", diff.get(0).getAttribute("implementation"));
    List<String> topics = new ArrayList<>();
    for (Element listener : elements(plugin, "listener")) {
      if (listener.getAttribute("class").equals("cn.yooss.heic.ui.HeicActivationListener")) topics.add(listener.getAttribute("topic"));
    }
    assertEquals(List.of("com.intellij.openapi.application.ApplicationActivationListener"), topics);
    List<Element> projectListeners = elements(plugin, "projectListeners");
    assertEquals(1, projectListeners.size());
    NodeList opened = projectListeners.get(0).getElementsByTagName("listener");
    assertEquals(1, opened.getLength());
    assertEquals("cn.yooss.heic.ui.HeicFileOpenedListener", ((Element) opened.item(0)).getAttribute("class"));
    assertEquals("com.intellij.openapi.fileEditor.FileEditorManagerListener", ((Element) opened.item(0)).getAttribute("topic"));
  }

  /** Loads the listener and extension classes, whose IDE supertypes are Java 21 bytecode in the IDE compiled against. */
  @Test
  @Tag("platform")
  void referencedClassesExist() throws Exception {
    List<String> classes = new ArrayList<>();
    for (String name : implementationClasses(parse("META-INF/plugin.xml"))) {
      classes.add(name);
      Class.forName(name, false, getClass().getClassLoader());
    }
    assertEquals(10, classes.size(), classes::toString);
  }

  @Test
  void referencedClassesArePluginClasses() throws Exception {
    List<String> classes = implementationClasses(parse("META-INF/plugin.xml"));
    assertEquals(10, classes.size(), classes::toString);
    for (String name : classes) {
      assertTrue(name.startsWith("cn.yooss.heic."), name);
      assertNotNull(getClass().getClassLoader().getResource(name.replace('.', '/') + ".class"), name);
    }
  }

  private static List<String> implementationClasses(Document plugin) {
    List<String> classes = new ArrayList<>();
    for (Element listener : elements(plugin, "listener")) classes.add(listener.getAttribute("class"));
    for (Element provider : elements(plugin, "fileIconProvider")) classes.add(provider.getAttribute("implementation"));
    for (Element provider : elements(plugin, "fileEditorProvider")) classes.add(provider.getAttribute("implementation"));
    for (Element provider : elements(plugin, "editorNotificationProvider")) classes.add(provider.getAttribute("implementation"));
    for (Element extension : elements(plugin, "diff.DiffExtension")) classes.add(extension.getAttribute("implementation"));
    for (Element group : elements(plugin, "notificationGroup")) {
      if (!group.getAttribute("implementation").isEmpty()) classes.add(group.getAttribute("implementation"));
    }
    return classes;
  }

  /** The reader registration hook only runs for Image files (the file type the HEIC extensions are merged into). */
  @Test
  void readerRegistrarIsAskedForImageFilesOnly() throws Exception {
    List<Element> providers = elements(parse("META-INF/plugin.xml"), "fileEditorProvider");
    assertEquals(1, providers.size());
    assertEquals("cn.yooss.heic.HeicReaderRegistrar", providers.get(0).getAttribute("implementation"));
    assertEquals("Image", providers.get(0).getAttribute("fileType"));
  }

  private static Document parse(String resource) throws Exception {
    try (InputStream in = open(resource)) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      return factory.newDocumentBuilder().parse(in);
    }
  }

  private static Properties properties(String resource) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = open(resource)) {
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return properties;
  }

  private static InputStream open(String resource) throws IOException {
    ClassLoader loader = PluginDescriptorTest.class.getClassLoader();
    URL url;
    if (resource.equals("META-INF/plugin.xml")) {
      // The IDE's own jars on the test class path contain META-INF/plugin.xml files as well: take ours, from the
      // resource root that holds our (uniquely named) message bundle.
      String bundle = "messages/HeicBundle.properties";
      URL anchor = loader.getResource(bundle);
      assertNotNull(anchor, bundle);
      String location = anchor.toString(); // file:... or jar:file:...!/messages/HeicBundle.properties
      url = URI.create(location.substring(0, location.length() - bundle.length()) + resource).toURL();
    }
    else {
      url = loader.getResource(resource);
    }
    assertNotNull(url, resource);
    return url.openStream();
  }

  private static String text(Document document, String tag) {
    List<Element> found = elements(document, tag);
    assertEquals(1, found.size(), tag);
    return found.get(0).getTextContent().trim();
  }

  private static List<Element> elements(Document document, String tag) {
    NodeList nodes = document.getElementsByTagName(tag);
    Element[] result = new Element[nodes.getLength()];
    for (int i = 0; i < result.length; i++) result[i] = (Element)nodes.item(i);
    return Arrays.asList(result);
  }
}
