package app.ui.pet;

import app.character.animation.AnimationType;
import app.character.animation.sprite.SpriteAnimationPlayer;
import app.character.animation.sprite.SpriteFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PetFrameSubscriptionTest {
    @TempDir Path temporary;
    @Test void frameFeedUsesCompleteHiDpiFramesAndUnsubscribesWithoutStoppingThePlayer() throws Exception {
        String oldRenderer = System.getProperty("closure.animation"), oldAssets = System.getProperty("closure.petAssetsDirectory");
        System.setProperty("closure.animation", "sprite");
        System.setProperty("closure.petAssetsDirectory", SpriteFixtures.create(temporary).toString());
        AtomicReference<PetAnimationPanel> panel = new AtomicReference<>();
        AtomicReference<Runnable> close = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger(); CountDownLatch frames = new CountDownLatch(5);
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new PetAnimationPanel(220, 220, null));
                close.set(panel.get().observeFrames(image -> {
                    assertEquals(440, image.getWidth()); assertEquals(440, image.getHeight());
                    assertEquals(128, image.getRGB(220, 220) >>> 24);
                    count.incrementAndGet(); frames.countDown();
                }));
                panel.get().play(AnimationType.IDLE);
            });
            assertTrue(frames.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(close.get()); int stopped = count.get();
            SwingUtilities.invokeAndWait(() -> { close.get().run(); panel.get().play(AnimationType.HAPPY); });
            Thread.sleep(200); SwingUtilities.invokeAndWait(() -> { });
            assertEquals(stopped, count.get()); assertTrue(panel.get().animationPlayer().isPlaying());
        } finally {
            if (panel.get() != null) {
                SwingUtilities.invokeAndWait(panel.get()::dispose);
                assertTrue(((SpriteAnimationPlayer) panel.get().animationPlayer()).awaitClosed(1500));
            }
            if (oldRenderer == null) System.clearProperty("closure.animation"); else System.setProperty("closure.animation", oldRenderer);
            if (oldAssets == null) System.clearProperty("closure.petAssetsDirectory"); else System.setProperty("closure.petAssetsDirectory", oldAssets);
        }
    }
}
