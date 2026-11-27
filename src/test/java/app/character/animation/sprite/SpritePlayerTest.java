package app.character.animation.sprite;

import app.character.CharacterController;
import app.character.animation.AnimationController;
import app.character.animation.AnimationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SpritePlayerTest {
    @TempDir Path root;
    @Test void activityAndSpeechRecoverAfterAnInteractionAndDisposeTerminatesWorker() throws Exception {
        var player = new SpriteAnimationPlayer(new JLabel(), 220, 220, SpriteModel.load(SpriteFixtures.create(root)));
        var character = new CharacterController(new AnimationController(player));
        try {
            character.play(AnimationType.TAP);
            Runnable done = character.thinkingStarted();
            await(() -> player.currentClip().equals("think_idle"));
            character.setTalking(true); await(() -> player.currentClip().equals("talk"));
            character.setTalking(false); await(() -> player.currentClip().equals("think_idle"));
            done.run(); await(() -> player.currentClip().equals("idle"));
            assertFalse(player.supportsWalking()); assertTrue(player.model().cachedPages() <= 4);
        } finally { character.close(); player.close(); }
        assertTrue(player.awaitClosed(1500)); assertFalse(player.isPlaying());
    }
    @Test void preemptionStopAndCloseInvalidateOldCompletions() throws Exception {
        var player = new SpriteAnimationPlayer(new JLabel(), 220, 220, SpriteModel.load(SpriteFixtures.create(root)));
        AtomicInteger callbacks = new AtomicInteger();
        try {
            player.playOnce(AnimationType.GREETING, callbacks::incrementAndGet);
            player.play(AnimationType.TALK); await(() -> player.currentClip().equals("talk"));
            Thread.sleep(260); SwingUtilities.invokeAndWait(() -> { }); assertEquals(0, callbacks.get());
            CountDownLatch finished = new CountDownLatch(1);
            player.playOnce(AnimationType.HAPPY, finished::countDown);
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            player.playOnce(AnimationType.TAP, callbacks::incrementAndGet); player.stop();
            Thread.sleep(260); SwingUtilities.invokeAndWait(() -> { }); assertEquals(0, callbacks.get());
            player.playOnce(AnimationType.GREETING, callbacks::incrementAndGet); player.close();
            player.playOnce(AnimationType.HAPPY, callbacks::incrementAndGet);
        } finally { player.close(); }
        assertTrue(player.awaitClosed(1500)); SwingUtilities.invokeAndWait(() -> { }); assertEquals(0, callbacks.get());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
