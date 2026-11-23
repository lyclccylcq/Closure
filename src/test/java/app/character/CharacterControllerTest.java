package app.character;

import app.character.animation.*;
import app.voice.model.SpeechEmotion;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class CharacterControllerTest {
    private static final class Player implements AnimationPlayer {
        AnimationType current;
        boolean playing;
        List<Runnable> completions = new ArrayList<>();
        public void play(AnimationType animation) { current = animation; playing = true; }
        public void playOnce(AnimationType animation, Runnable completion) { play(animation); completions.add(completion); }
        public void stop() { current = null; playing = false; }
        public boolean isPlaying() { return playing; }
    }

    @Test
    void finishingSpeechDuringTapRestoresIdleRatherThanTheOldSpeechState() {
        var player = new Player(); var controller = new CharacterController(new AnimationController(player));
        controller.setTalking(true);
        controller.play(AnimationType.TAP);
        controller.setTalking(false);
        assertEquals(AnimationType.TAP, player.current);
        player.completions.getFirst().run();
        assertEquals(AnimationType.IDLE, player.current);
    }

    @Test
    void startingSpeechDuringTapRestoresTalkingAndLateCompletionsCannotInterruptNewTaps() {
        var player = new Player(); var controller = new CharacterController(new AnimationController(player));
        controller.play(AnimationType.TAP);
        controller.setTalking(true);
        controller.play(AnimationType.TAP);
        player.completions.get(0).run();
        assertEquals(AnimationType.TAP, player.current);
        player.completions.get(1).run();
        assertEquals(AnimationType.TALK, player.current);
    }

    @Test
    void overlappingThinkingRequestsArePairedAndSpeechHasPriority() {
        var player = new Player(); var controller = new CharacterController(new AnimationController(player));
        Runnable a = controller.thinkingStarted(), b = controller.thinkingStarted();
        a.run(); a.run();
        assertEquals(CharacterState.THINKING, controller.state());
        controller.setTalking(true);
        assertEquals(CharacterState.TALKING, controller.state());
        controller.setTalking(false);
        assertEquals(AnimationType.THINKING, player.current);
        b.run();
        assertEquals(AnimationType.IDLE, player.current);
    }

    @Test
    void positiveEmotionDoesNotInterruptAnExplicitTapAndCloseInvalidatesAllCallbacks() {
        var player = new Player(); var controller = new CharacterController(new AnimationController(player));
        controller.play(AnimationType.TAP);
        controller.replyEmotion(SpeechEmotion.CHEERFUL);
        assertEquals(AnimationType.TAP, player.current);
        assertEquals(1, player.completions.size());
        controller.close();
        player.completions.getFirst().run();
        controller.setTalking(true);
        controller.thinkingStarted().run();
        assertNull(player.current);
        assertFalse(player.playing);
    }

    @Test
    void chatErrorAndDisposalReleaseAllRequestsWithoutLateEmotionOrThinking() {
        var player = new Player(); var controller = new CharacterController(new AnimationController(player));
        var activity = new ChatActivity(controller);
        var failed = activity.start(); var pending = activity.start();
        failed.close();
        assertEquals(CharacterState.THINKING, controller.state());
        activity.close();
        assertEquals(CharacterState.IDLE, controller.state());
        pending.complete(SpeechEmotion.CHEERFUL);
        activity.start().complete(SpeechEmotion.TEASING);
        assertEquals(CharacterState.IDLE, controller.state());
        assertTrue(player.completions.isEmpty());
    }

    @Test
    void cheerfulReplyTriggersOneShotAndSleepWakesForSpeech() {
        var player = new Player(); var controller = new CharacterController(new AnimationController(player));
        var request = new ChatActivity(controller).start();
        request.complete(SpeechEmotion.CHEERFUL);
        assertEquals(AnimationType.HAPPY, player.current);
        request.complete(SpeechEmotion.CHEERFUL);
        assertEquals(1, player.completions.size());
        player.completions.getFirst().run();
        controller.play(AnimationType.SLEEP);
        assertEquals(CharacterState.SLEEPING, controller.state());
        controller.setTalking(true);
        controller.setTalking(false);
        assertEquals(CharacterState.IDLE, controller.state());
    }

    @Test
    void concurrentCompletionsAndDisposalLeaveNoOutstandingThinking() throws Exception {
        var player = new Player(); var controller = new CharacterController(new AnimationController(player));
        var activity = new ChatActivity(controller);
        var requests = new ArrayList<ChatActivity.Request>();
        for (int i = 0; i < 64; i++) requests.add(activity.start());
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var request : requests) futures.add(threads.submit(() -> request.complete(SpeechEmotion.NEUTRAL)));
            futures.add(threads.submit(activity::close));
            for (var future : futures) future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(CharacterState.IDLE, controller.state());
        assertEquals(AnimationType.IDLE, player.current);
    }
}
