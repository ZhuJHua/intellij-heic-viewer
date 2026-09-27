package cn.yooss.heic;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the plugin jar (the one Gradle packages, {@code heic.test.pluginJar}; else the compiled classes) against what
 * the compiler or Plugin Verifier would not catch:
 * <ul>
 *   <li>class file major version at most 61 (Java 17): IntelliJ 2024.1 runs on JBR 17, and Plugin Verifier does not
 *   check class versions;</li>
 *   <li>no {@code java.lang.foreign} (Java 22), no Java 21 pattern-switch or record-pattern runtime classes;</li>
 *   <li>no records and no {@code java.lang.runtime.ObjectMethods}: on JDK 17 their bootstrap caches keep the plugin class
 *   loader alive, so the plugin could not be unloaded without a restart;</li>
 *   <li>the JNA rules of {@code cn.yooss.heic.backend.jna.JnaLibraries}: no plugin class extends or implements a JNA
 *   type ({@code Library}, {@code Structure}, {@code Callback}, ...), no {@code Memory}, no {@code Native.load},
 *   {@code Native.loadLibrary} or {@code Native.register} (JNA's static caches would pin the class loader).</li>
 * </ul>
 */
class BytecodeLevelTest {
  private static final int JAVA_17 = 61;
  private static final List<String> FORBIDDEN_CLASS_PREFIXES = List.of(
      "java/lang/foreign/", "java/lang/runtime/ObjectMethods", "java/lang/runtime/SwitchBootstraps",
      "java/lang/MatchException", "java/lang/Record", "com/sun/jna/Memory");
  private static final List<String> FORBIDDEN_NATIVE_METHODS = List.of("load", "loadLibrary", "register");

  @Test
  void pluginClassesRunOnJava17AndStayUnloadable() throws Exception {
    Map<String, byte[]> classes = pluginClasses();
    assertTrue(classes.size() > 30, "found only " + classes.keySet());
    assertTrue(classes.containsKey("cn/yooss/heic/mac/jna/JnaMacApi.class"), "scanned the wrong place: " + classes.keySet());
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
      problems.addAll(check(entry.getKey(), entry.getValue()));
    }
    assertEquals(Collections.emptyList(), problems);
  }

  /** The checks themselves, on classes of the test run that violate them. */
  @Test
  void checksDetectViolations() throws IOException {
    assertTrue(check("Record.class", bytes(SampleRecord.class)).stream().anyMatch(p -> p.contains("java/lang/Record")));
    assertTrue(check("Record.class", bytes(SampleRecord.class)).stream().anyMatch(p -> p.contains("ObjectMethods")));
    assertTrue(check("Library.class", bytes(SampleLibrary.class)).stream().anyMatch(p -> p.contains("com/sun/jna/Library")));
    assertEquals(Collections.emptyList(), check("ChildJvm.class", bytes(ChildJvm.class)));
  }

  interface SampleLibrary extends com.sun.jna.Library {
  }

  record SampleRecord(int value) {
  }

  static List<String> check(String name, byte[] bytes) throws IOException {
    List<String> problems = new ArrayList<>();
    DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
    if (in.readInt() != 0xCAFEBABE) return List.of(name + ": not a class file");
    in.readUnsignedShort(); // minor
    int major = in.readUnsignedShort();
    if (major > JAVA_17) problems.add(name + ": class file version " + major + " > " + JAVA_17 + " (Java 17)");

    int count = in.readUnsignedShort();
    Object[] pool = new Object[count];
    int[][] refs = new int[count][];
    int[] tags = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      tags[i] = tag;
      switch (tag) {
        case 1: pool[i] = in.readUTF(); break;
        case 3: case 4: in.readInt(); break;
        case 5: case 6: in.readLong(); i++; break;
        case 7: case 8: case 16: case 19: case 20: refs[i] = new int[]{in.readUnsignedShort()}; break;
        case 9: case 10: case 11: case 12: case 17: case 18:
          refs[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
          break;
        case 15: in.readUnsignedByte(); refs[i] = new int[]{in.readUnsignedShort()}; break;
        default: return List.of(name + ": unknown constant pool tag " + tag);
      }
    }
    for (int i = 1; i < count; i++) {
      if (tags[i] == 1 && ((String) pool[i]).contains("Ljava/lang/foreign/")) { // a descriptor
        problems.add(name + ": descriptor mentions java.lang.foreign: " + pool[i]);
      }
      if (tags[i] == 7) { // CONSTANT_Class
        String className = (String) pool[refs[i][0]];
        for (String forbidden : FORBIDDEN_CLASS_PREFIXES) {
          if (className.startsWith(forbidden) || className.contains("L" + forbidden)) {
            problems.add(name + ": references " + className);
          }
        }
      }
      if (tags[i] == 10) { // CONSTANT_Methodref
        String owner = (String) pool[refs[refs[i][0]][0]];
        String method = (String) pool[refs[refs[i][1]][0]];
        if (owner.equals("com/sun/jna/Native") && FORBIDDEN_NATIVE_METHODS.contains(method)) {
          problems.add(name + ": calls Native." + method + " (use JnaLibraries.open and Function.invoke*)");
        }
      }
    }
    in.readUnsignedShort(); // access flags
    in.readUnsignedShort(); // this_class
    List<String> supertypes = new ArrayList<>();
    int superClass = in.readUnsignedShort();
    if (superClass != 0) supertypes.add((String) pool[refs[superClass][0]]);
    int interfaces = in.readUnsignedShort();
    for (int i = 0; i < interfaces; i++) supertypes.add((String) pool[refs[in.readUnsignedShort()][0]]);
    for (String supertype : supertypes) {
      if (supertype.startsWith("com/sun/jna/")) {
        problems.add(name + ": extends or implements " + supertype + " (JNA caches keyed by plugin classes pin the class loader)");
      }
      if (supertype.equals("java/lang/Record")) problems.add(name + ": is a record (java/lang/Record)");
    }
    return problems;
  }

  /** The classes of the plugin jar Gradle built, or of the compiled main classes. */
  private static Map<String, byte[]> pluginClasses() throws IOException, URISyntaxException {
    Map<String, byte[]> classes = new TreeMap<>();
    String jar = System.getProperty("heic.test.pluginJar");
    if (jar != null && !jar.isEmpty()) {
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(Paths.get(jar)))) {
        for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
          if (entry.getName().endsWith(".class")) classes.put(entry.getName(), readAll(in));
        }
      }
      return classes;
    }
    String resource = HeicSupport.class.getName().replace('.', '/') + ".class";
    URL url = HeicSupport.class.getClassLoader().getResource(resource);
    assertNotNull(url);
    assertEquals("file", url.getProtocol(), "set heic.test.pluginJar to scan a jar: " + url);
    Path file = Paths.get(url.toURI());
    Path root = file;
    for (int i = 0; i < resource.split("/").length; i++) root = root.getParent();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path path : (Iterable<Path>) files::iterator) {
        if (path.toString().endsWith(".class")) classes.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
      }
    }
    return classes;
  }

  private static byte[] bytes(Class<?> type) throws IOException {
    try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
      assertNotNull(in, type.getName());
      return readAll(in);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
    return out.toByteArray();
  }
}
