package cn.yooss.heic;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.intellij.images.fileTypes.ImageFileTypeManager;
import org.intellij.images.vfs.IfsUtil;

import java.awt.image.BufferedImage;

/**
 * The plugin inside a light IDE (IntelliJ test framework, the `test` task only): plugin.xml maps the HEIC extensions to
 * the platform's Image file type, and the IDE's own image loading ({@code IfsUtil}, used by the image editor and the
 * diff) decodes a HEIC file through our reader where a system decoder is available.
 */
public class HeicPlatformIntegrationTest extends BasePlatformTestCase {
  public void testHeicExtensionsAreImages() {
    FileType image = ImageFileTypeManager.getInstance().getImageFileType();
    for (String extension : HeicImageReaderSpi.SUFFIXES) {
      assertSame(extension, image, FileTypeManager.getInstance().getFileTypeByExtension(extension));
    }
    assertTrue(HeicFileTypeMappingRepair.unmappedExtensions().isEmpty());
  }

  public void testIfsUtilDecodesHeicThroughOurReader() throws Exception {
    assertTrue(HeicSupport.register());
    try {
      VirtualFile file = myFixture.getTempDirFixture().createFile("photo.heic");
      byte[] content = Fixtures.bytes("exif6_apple.heic");
      WriteAction.runAndWait(() -> file.setBinaryContent(content));
      assertTrue(ImageFileTypeManager.getInstance().isImage(file));
      if (!SystemDecoder.isAvailable()) return; // e.g. Windows/Linux without the system codecs
      assertEquals("heic", IfsUtil.getFormat(file));
      BufferedImage decoded = IfsUtil.getImage(file);
      assertNotNull(decoded);
      assertEquals("400x600 TL=blue TR=red BL=white BR=green marker=TR", Fixtures.layout(decoded));
    }
    finally {
      HeicSupport.unregister();
    }
  }
}
