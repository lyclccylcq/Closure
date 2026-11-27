package app.character.animation.sprite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SpriteModelTest {
    @TempDir Path root;
    @Test void preservesAlphaAndReadsAllClipsWithBoundedCache() throws Exception {
        SpriteModel model = SpriteModel.load(SpriteFixtures.create(root));
        assertTrue(model.synthetic()); assertEquals(30, model.fps());
        for (String name : SpriteModel.CLIPS) {
            for (int i = 0; i < model.frames(name); i++) {
                BufferedImage image = model.frame(name, i);
                assertEquals(32, image.getWidth()); assertEquals(28, image.getHeight());
                assertEquals(0, image.getRGB(0, 0) >>> 24);
                assertEquals(128, image.getRGB(16, 14) >>> 24);
                assertTrue(model.cachedPages() <= SpriteModel.MAX_CACHED_PAGES);
            }
        }
        assertEquals(4, model.cachedPages()); model.clearCache(); assertEquals(0, model.cachedPages());
    }
    @Test void rejectsTraversalAndMissingClipsBeforePlayback() throws Exception {
        SpriteFixtures.create(root);
        var data = SpriteFixtures.properties(root); data.setProperty("idle.page.0", "../outside.png");
        SpriteFixtures.write(root, data);
        assertThrows(IllegalArgumentException.class, () -> SpriteModel.load(root));
        data.setProperty("idle.page.0", "idle-0.png"); data.remove("talk.frames"); SpriteFixtures.write(root, data);
        assertThrows(IllegalArgumentException.class, () -> SpriteModel.load(root));
    }
    @Test void rejectsCorruptPagesAndUnexpectedDimensions() throws Exception {
        SpriteFixtures.create(root);
        SpriteModel model = SpriteModel.load(root);
        Files.write(root.resolve("idle-0.png"), new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> model.frame("idle", 0));
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", root.resolve("idle-0.png").toFile());
        var data = SpriteFixtures.properties(root); data.setProperty("idle.sha256.0", SpriteFixtures.hash(root.resolve("idle-0.png")));
        SpriteFixtures.write(root, data);
        assertThrows(IllegalArgumentException.class, () -> SpriteModel.load(root).frame("idle", 0));
    }
    @Test void rejectsUnboundedManifestsAndSymlinkEscapes() throws Exception {
        SpriteFixtures.create(root);
        var data = SpriteFixtures.properties(root); data.setProperty("pageFrames", "1000000"); SpriteFixtures.write(root, data);
        assertThrows(IllegalArgumentException.class, () -> SpriteModel.load(root));
        data.setProperty("pageFrames", "4");
        Path outside = Files.createTempFile(root.getParent(), "sprite-outside-", ".png");
        try {
            Files.createSymbolicLink(root.resolve("escape.png"), outside);
            data.setProperty("idle.page.0", "escape.png"); SpriteFixtures.write(root, data);
            assertThrows(IllegalArgumentException.class, () -> SpriteModel.load(root));
        } finally { Files.deleteIfExists(outside); }
    }
}
