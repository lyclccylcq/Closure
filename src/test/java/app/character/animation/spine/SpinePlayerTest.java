package app.character.animation.spine;

import app.character.animation.AnimationType;
import app.ui.pet.PetAnimationPanel;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SpinePlayerTest {
    @Test
    void walkingAndSeatedSpeechPublishRealLoopingFramesAndCloseCleanly() throws Exception {
        var label=new JLabel();
        var player=new SpineAnimationPlayer(label,220,220,SpineModel.bundled());
        var frames=new CountDownLatch(2);
        label.addPropertyChangeListener("icon",event -> frames.countDown());
        try{
            player.play(AnimationType.WALK);
            assertTrue(frames.await(5,TimeUnit.SECONDS));
            assertTrue(player.isPlaying());
            player.play(AnimationType.THINKING_TALK);
            SwingUtilities.invokeAndWait(() -> assertNotNull(label.getIcon()));
        }finally{ player.close(); }
        assertTrue(player.awaitClosed(1500));
    }

    @Test
    void aRealOneShotCompletesOnceAndClosingTerminatesTheRenderer() throws Exception {
        var label = new JLabel();
        var player = new SpineAnimationPlayer(label, 220, 220, SpineModel.bundled());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        try {
            player.playOnce(AnimationType.TAP, () -> { count.incrementAndGet(); completed.countDown(); });
            assertTrue(completed.await(6, TimeUnit.SECONDS));
            assertEquals(1, count.get());
            assertFalse(player.isPlaying());
        } finally { player.close(); }
        assertTrue(player.awaitClosed(1500));
    }

    @Test
    void closeCancelsPendingFramesAndCompletionsWithoutTouchingSwingAfterwards() throws Exception {
        var label = new JLabel();
        var player = new SpineAnimationPlayer(label, 220, 220, SpineModel.bundled());
        var initial = label.getIcon();
        AtomicInteger completed = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            player.playOnce(AnimationType.TAP, completed::incrementAndGet);
            player.close();
        });
        assertTrue(player.awaitClosed(1500));
        SwingUtilities.invokeAndWait(() -> { });
        assertSame(initial, label.getIcon());
        assertEquals(0, completed.get());
    }

    @Test
    void liftedDragTransitionsToAShortLandingAndCompletesOnce() throws Exception {
        var label=new JLabel();
        var player=new SpineAnimationPlayer(label,220,220,SpineModel.bundled());
        var frames=new CountDownLatch(2);
        label.addPropertyChangeListener("icon",event->frames.countDown());
        var landed=new CountDownLatch(1);
        var count=new AtomicInteger();
        try{
            player.play(AnimationType.DRAGGING);
            player.setDragMotion(1);
            assertTrue(frames.await(2,TimeUnit.SECONDS));
            player.playOnce(AnimationType.LANDING,()->{count.incrementAndGet();landed.countDown();});
            assertTrue(landed.await(2,TimeUnit.SECONDS));
            assertEquals(1,count.get());
            assertFalse(player.isPlaying());
        }finally{player.close();}
        assertTrue(player.awaitClosed(1500));
    }

    @Test
    void thePanelUsesSpineAndSupportsExplicitAndMissingResourceFallback() throws Exception {
        String oldMode = System.getProperty("closure.animation");
        String oldRoot = System.getProperty("closure.spine.resources");
        try {
            System.setProperty("closure.animation", "spine");
            System.clearProperty("closure.spine.resources");
            assertPanelRenderer("spine");
            System.setProperty("closure.spine.resources", "/missing-spine/");
            assertPanelRenderer("frames");
            System.clearProperty("closure.spine.resources");
            System.setProperty("closure.animation", "frames");
            assertPanelRenderer("frames");
        } finally {
            restore("closure.animation", oldMode);
            restore("closure.spine.resources", oldRoot);
        }
    }

    private static void assertPanelRenderer(String expected) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new PetAnimationPanel(220, 220, null);
            try { assertEquals(expected, panel.rendererName()); }
            finally { panel.dispose(); }
        });
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}
