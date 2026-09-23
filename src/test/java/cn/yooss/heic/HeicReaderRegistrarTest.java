package cn.yooss.heic;

import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.BinaryLightVirtualFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.MAC)
class HeicReaderRegistrarTest {
  @AfterEach
  void cleanUp() {
    HeicSupport.unregister();
  }

  /**
   * `studio diff a.heic b.heic` while the IDE is not running: appFrameCreated never fires, but the providers of the
   * file's type are asked before the diff creates its image viewers.
   */
  @Test
  void acceptRegistersTheReaderButNeverProvidesAnEditor() {
    FileEditorProvider provider = new HeicReaderRegistrar();
    assertInstanceOf(DumbAware.class, provider, "also asked while indexing");
    Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                                                       (proxy, method, args) -> {
                                                         throw new UnsupportedOperationException(method.getName());
                                                       });
    BinaryLightVirtualFile file = new BinaryLightVirtualFile("a.heic", new byte[0]);

    assertFalse(HeicSupport.isRegistered());
    assertFalse(provider.accept(project, file));
    assertTrue(HeicSupport.isRegistered());
    assertFalse(provider.accept(project, file));
    assertFalse(provider.acceptRequiresReadAction());
    assertEquals(FileEditorPolicy.NONE, provider.getPolicy());
    assertThrows(UnsupportedOperationException.class, () -> provider.createEditor(project, file));
  }
}
