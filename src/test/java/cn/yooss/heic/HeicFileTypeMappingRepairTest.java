package cn.yooss.heic;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.util.Condition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeicFileTypeMappingRepairTest {
  /**
   * The repair waits in the EDT queue while a modal dialog (Settings | Plugins) is open. It must expire as soon as
   * beforePluginUnload has deregistered the reader, so that DynamicPlugins purges it instead of it pinning the plugin
   * class loader.
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
