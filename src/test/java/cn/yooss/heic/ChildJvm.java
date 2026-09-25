package cn.yooss.heic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line for a fresh JVM that runs a test's {@code main} class with the same runtime, class path and JNA setup as
 * the test JVM (tests that need an uninitialized ImageIO or JNA). The class path goes into a {@code java @argfile}: the
 * IDE's jars make it longer than the Windows command line limit.
 */
public final class ChildJvm {
  private ChildJvm() {
  }

  public static List<String> command(Class<?> mainClass, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Djava.awt.headless=true");
    command.add("--add-opens=java.base/java.lang=ALL-UNNAMED"); // like the IDE launcher (see JnaLibraries)
    if (Runtime.version().feature() >= 22) command.add("--enable-native-access=ALL-UNNAMED"); // JNI without warnings
    for (String property : new String[]{"jna.boot.library.path", "jna.nosys", "jna.noclasspath"}) {
      String value = System.getProperty(property);
      if (value != null) command.add("-D" + property + "=" + value);
    }
    command.add("@" + classPathArgumentFile());
    command.add(mainClass.getName());
    command.addAll(List.of(arguments));
    return command;
  }

  /** A launcher argument file with {@code -cp <the test class path>}, deleted when the test JVM exits. */
  private static Path classPathArgumentFile() {
    try {
      Path file = Files.createTempFile("heic-test-classpath", ".args");
      file.toFile().deleteOnExit();
      // Argument files treat backslashes in quoted arguments as escapes (Windows paths).
      String classPath = System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"");
      Files.write(file, ("-cp\n\"" + classPath + "\"\n").getBytes(StandardCharsets.UTF_8));
      return file;
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
