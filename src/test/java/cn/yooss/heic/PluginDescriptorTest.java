package cn.yooss.heic;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the plugin descriptors, the Java constants and the message bundles consistent: a mismatch would not fail the
 * build, but silently break dynamic loading (plugin id), settings (ids, texts) or the macOS-only part.
 */
class PluginDescriptorTest {
  private static final String MACOS_CONFIG = "heic-viewer-macos.xml";

  @Test
  void pluginIdMatchesTheConstantUsedByTheDynamicPluginListener() throws Exception {
    Document plugin = parse("META-INF/plugin.xml");
    assertEquals(HeicSupport.PLUGIN_ID, text(plugin, "id"));
    assertEquals("cn.yooss.heic-viewer", HeicSupport.PLUGIN_ID);
    assertEquals("HEIC Viewer", text(plugin, "name"));
  }

  @Test
  void everythingIsContributedThroughTheOptionalMacOsDependency() throws Exception {
    Document plugin = parse("META-INF/plugin.xml");
    List<Element> optional = new ArrayList<>();
    for (Element depends : elements(plugin, "depends")) {
      if ("true".equals(depends.getAttribute("optional"))) optional.add(depends);
    }
    assertEquals(1, optional.size(), "exactly one optional dependency");
    assertEquals("com.intellij.modules.os.mac", optional.getFirst().getTextContent().trim());
    assertEquals(MACOS_CONFIG, optional.getFirst().getAttribute("config-file"));
    assertEquals(0, plugin.getElementsByTagName("extensions").getLength(), "extensions belong into " + MACOS_CONFIG);
    assertEquals(0, plugin.getElementsByTagName("applicationListeners").getLength(), "listeners belong into " + MACOS_CONFIG);
  }

  @Test
  void fileTypeExtensionsMatchTheReaderSuffixes() throws Exception {
    List<Element> fileTypes = elements(parse("META-INF/" + MACOS_CONFIG), "fileType");
    assertEquals(1, fileTypes.size());
    assertEquals("Image", fileTypes.getFirst().getAttribute("name"));
    assertEquals(String.join(";", HeicImageReaderSpi.SUFFIXES), fileTypes.getFirst().getAttribute("extensions"));
  }

  @Test
  void advancedSettingsMatchTheConstantsAndHaveTextsInEveryBundle() throws Exception {
    List<Element> settings = elements(parse("META-INF/" + MACOS_CONFIG), "advancedSetting");
    Set<String> ids = new TreeSet<>();
    for (Element setting : settings) {
      ids.add(setting.getAttribute("id"));
      assertEquals("messages.HeicBundle", setting.getAttribute("bundle"));
      assertEquals("group.advanced.settings.heic", setting.getAttribute("groupKey"));
    }
    assertEquals(new TreeSet<>(Set.of(HeicSettings.MAX_MEGAPIXELS, HeicSettings.PROJECT_VIEW_THUMBNAILS)), ids);

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

  @Test
  void referencedClassesExist() throws Exception {
    Document config = parse("META-INF/" + MACOS_CONFIG);
    List<String> classes = new ArrayList<>();
    for (Element listener : elements(config, "listener")) classes.add(listener.getAttribute("class"));
    for (Element provider : elements(config, "fileIconProvider")) classes.add(provider.getAttribute("implementation"));
    for (Element provider : elements(config, "fileEditorProvider")) classes.add(provider.getAttribute("implementation"));
    assertEquals(6, classes.size(), classes::toString);
    for (String name : classes) {
      assertTrue(name.startsWith("cn.yooss.heic."), name);
      Class.forName(name, false, getClass().getClassLoader());
    }
  }

  /** The reader registration hook only runs for Image files (the file type the HEIC extensions are merged into). */
  @Test
  void readerRegistrarIsAskedForImageFilesOnly() throws Exception {
    List<Element> providers = elements(parse("META-INF/" + MACOS_CONFIG), "fileEditorProvider");
    assertEquals(1, providers.size());
    assertEquals(HeicReaderRegistrar.class.getName(), providers.getFirst().getAttribute("implementation"));
    assertEquals("Image", providers.getFirst().getAttribute("fileType"));
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
      // The IDE's own jars on the test classpath contain META-INF/plugin.xml files as well: take ours, next to the
      // (uniquely named) macOS config file.
      URL config = loader.getResource("META-INF/" + MACOS_CONFIG);
      assertNotNull(config, MACOS_CONFIG);
      String location = config.toString(); // file:... or jar:file:...!/META-INF/heic-viewer-macos.xml
      url = URI.create(location.substring(0, location.length() - MACOS_CONFIG.length()) + "plugin.xml").toURL();
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
    return found.getFirst().getTextContent().trim();
  }

  private static List<Element> elements(Document document, String tag) {
    NodeList nodes = document.getElementsByTagName(tag);
    Element[] result = new Element[nodes.getLength()];
    for (int i = 0; i < result.length; i++) result[i] = (Element)nodes.item(i);
    return Arrays.asList(result);
  }
}
