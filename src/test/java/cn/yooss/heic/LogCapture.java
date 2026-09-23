package cn.yooss.heic;

import com.intellij.openapi.diagnostic.DefaultLogger;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the warnings of every logger obtained from {@link Logger#getInstance} while it is installed. */
final class LogCapture implements AutoCloseable {
  private final Logger.Factory previous = Logger.getFactory();
  private final List<String> warnings = new CopyOnWriteArrayList<>();

  LogCapture() {
    Logger.setFactory(category -> new DefaultLogger(category) {
      @Override
      public void warn(String message, Throwable t) {
        warnings.add(message + (t == null ? "" : " | " + t));
      }
    });
  }

  List<String> warnings() {
    return warnings;
  }

  @Override
  public void close() {
    Logger.setFactory(previous);
  }
}
