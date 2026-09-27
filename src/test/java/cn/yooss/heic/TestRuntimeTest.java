package cn.yooss.heic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The test tasks really run on the JDK they are named after (test: 25, testJdk21, testJdk17). */
class TestRuntimeTest {
  @Test
  void runsOnTheExpectedJdk() {
    String expected = System.getProperty("heic.test.javaVersion");
    assumeTrue(expected != null, "not run by Gradle");
    assertEquals(Integer.parseInt(expected), Runtime.version().feature(), System.getProperty("java.home"));
  }
}
