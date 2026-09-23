package cn.yooss.heic.thumbnail;

import cn.yooss.heic.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeicThumbnailsTest {
  @TempDir
  Path dir;

  @Test
  void onlyHeifExtensionsGetThumbnails() {
    for (String extension : new String[]{"heic", "HEIC", "Heif", "hif", "heics", "HEICS"}) {
      assertTrue(HeicThumbnails.hasThumbnailExtension(extension), extension);
    }
    for (String extension : new String[]{"png", "jpg", "avif", "heic2", "", "hei"}) {
      assertFalse(HeicThumbnails.hasThumbnailExtension(extension), extension);
    }
    assertFalse(HeicThumbnails.hasThumbnailExtension(null));
  }

  @Test
  void limits() {
    assertEquals(64L * 1024 * 1024, HeicThumbnails.MAX_FILE_BYTES);
    assertEquals(500, HeicThumbnails.MAX_CACHED_ICONS);
    assertEquals(2, HeicThumbnails.MAX_THREADS);
    assertEquals(16, HeicThumbnails.ICON_SIZE);
    assertEquals(1000, HeicThumbnails.UNLOAD_WAIT_MILLIS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rgb_sips.heic", "grid_libheif.heic", "seq.heics", "bands_2000x1200.heic"})
  void heifFilesAreReadCompletely(String name) throws IOException {
    byte[] expected = Fixtures.bytes(name);
    Path file = Files.write(dir.resolve("photo.heic"), expected);
    assertArrayEquals(expected, HeicThumbnails.readHeifFile(file));
  }

  /** A file merely named *.heic must not reach ImageIO.framework, which would pick another codec by content. */
  @ParameterizedTest
  @ValueSource(strings = {"png", "tiff", "bmp", "gif"})
  void otherFormatsNamedHeicAreRejected(String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(Fixtures.png("rgb.png"), format, out), format);
    Path file = Files.write(dir.resolve("disguised.heic"), out.toByteArray());
    IOException e = assertThrows(IOException.class, () -> HeicThumbnails.readHeifFile(file), format);
    assertTrue(e.getMessage().startsWith("Not a HEIC/HEIF file"), e.getMessage());
  }

  @Test
  void avifGarbageTinyAndEmptyFilesAreRejected() throws IOException {
    for (byte[] data : new byte[][]{Fixtures.bytes("rgb.avif"), Fixtures.bytes("garbage.heic"),
                                    Arrays.copyOf(Fixtures.bytes("rgb_sips.heic"), 12), new byte[0]}) {
      Path file = Files.write(dir.resolve("x.heic"), data);
      assertThrows(IOException.class, () -> HeicThumbnails.readHeifFile(file), "length " + data.length);
    }
  }
}
