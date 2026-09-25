package cn.yooss.heic;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line for a fresh JVM that runs a test's {@code main} class with the same runtime, class path and JNA setup as
 * the test JVM (tests that need an uninitialized ImageIO or JNA).
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
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass.getName());
    command.addAll(List.of(arguments));
    return command;
  }
}
