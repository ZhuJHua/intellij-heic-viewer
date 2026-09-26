package cn.yooss.heic;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.util.Condition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Needs the IDE's Application interface (Java 21 bytecode in 261). */
@Tag("platform")
class HeicFileTypeMappingRepairTest {
  /**
   * A repair queued on the EDT (e.g. behind a modal dialog) expires once the application is disposed or the reader is
   * deregistered on plugin unload, so that the platform drops it and it does not pin the plugin class loader.
   */
  @Test
  void queuedRepairExpiresWhenTheReaderIsDeregistered() {
    AtomicBoolean disposed = new AtomicBoolean();
    Application application = (Application) Proxy.newProxyInstance(
        Application.class.getClassLoader(), new Class<?>[]{Application.class}, (proxy, method, args) -> {
          if (method.getName().equals("isDisposed")) return disposed.get();
          throw new UnsupportedOperationException(method.getName());
        });
    Condition<Object> expired = HeicFileTypeMappingRepair.expired(application);

    HeicSupport.register(new HeicImageReaderSpi());
    try {
      assertFalse(expired.value(null), "registered, application alive");
      disposed.set(true);
      assertTrue(expired.value(null), "application disposed");
      disposed.set(false);
    }
    finally {
      HeicSupport.unregister();
    }
    assertTrue(expired.value(null), "reader deregistered (plugin unload)");
  }
}
