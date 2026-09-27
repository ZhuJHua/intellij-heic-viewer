package cn.yooss.heic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

  /** Output and exit code of a child JVM. */
  public static final class Result {
    public final int exitCode;
    public final String output;

    Result(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }

    /** The {@code RESULT key=value} lines of the output. */
    public Map<String, String> values() {
      Map<String, String> values = new HashMap<>();
      for (String line : output.split("\n")) {
        if (!line.startsWith("RESULT ")) continue;
        int eq = line.indexOf('=');
        if (eq > 0) values.put(line.substring("RESULT ".length(), eq), line.substring(eq + 1).trim());
      }
      return values;
    }
  }

  /**
   * Runs {@code mainClass} in a child JVM (see {@link #command}) and waits at most {@code timeoutSeconds}; a child that
   * does not end in time is killed and fails the test with its output so far. Output goes through a file, so a child
   * that hangs cannot block the test.
   */
  public static Result run(Class<?> mainClass, long timeoutSeconds, String... arguments) throws IOException, InterruptedException {
    Path log = Files.createTempFile("heic-test-child", ".log");
    try {
      ProcessBuilder builder = new ProcessBuilder(command(mainClass, arguments));
      builder.redirectErrorStream(true);
      builder.redirectOutput(log.toFile());
      Process process = builder.start();
      process.getOutputStream().close();
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
      if (!finished) throw new AssertionError("child JVM " + mainClass.getName() + " timed out after " + timeoutSeconds + " s:\n" + output);
      return new Result(process.exitValue(), output);
    }
    finally {
      Files.deleteIfExists(log);
    }
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
