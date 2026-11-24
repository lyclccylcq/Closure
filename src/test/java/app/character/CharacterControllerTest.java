package app.character;

import app.character.animation.*;
import app.voice.model.SpeechEmotion;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
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
    void cheerfulReplyTriggersOneShotAndSpeechReturnsToTheSleepingBase() {
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
        assertEquals(CharacterState.SLEEPING, controller.state());
        controller.userActivity();
        assertEquals(CharacterState.IDLE, controller.state());
    }

    @Test
    void visibleConversationKeepsThinkingAcrossRequestsSpeechEmotionAndIdleTime(){
        var now=new AtomicLong();
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player),now::get,Duration.ofMinutes(2));
        Runnable hide=controller.conversationOpened();
        assertEquals(AnimationType.THINKING,player.current);
        var activity=new ChatActivity(controller);
        activity.start().complete(SpeechEmotion.CHEERFUL);
        activity.start().close();
        assertEquals(AnimationType.THINKING,player.current);
        assertTrue(player.completions.isEmpty());
        controller.setTalking(true);
        assertEquals(AnimationType.THINKING_TALK,player.current);
        controller.setTalking(false);
        now.set(Duration.ofHours(1).toNanos());
        controller.checkIdle();
        assertEquals(AnimationType.THINKING,player.current);
        activity.close();
        assertEquals(CharacterState.THINKING,controller.state());
        hide.run();
        assertEquals(AnimationType.IDLE,player.current);
    }

    @Test
    void independentWindowLeasesReleaseExactlyOnceAndLastHideStartsAFreshIdleInterval(){
        var now=new AtomicLong();
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player),now::get,Duration.ofMinutes(2));
        Runnable first=controller.conversationOpened(),second=controller.conversationOpened();
        now.set(Duration.ofMinutes(10).toNanos());
        first.run(); first.run();
        assertEquals(CharacterState.THINKING,controller.state());
        second.run();
        assertEquals(CharacterState.IDLE,controller.state());
        now.addAndGet(Duration.ofMinutes(2).toNanos());
        controller.checkIdle();
        assertEquals(CharacterState.SLEEPING,controller.state());
        second.run();
        assertEquals(CharacterState.SLEEPING,controller.state());
        Runnable late=controller.conversationOpened();
        controller.close(); late.run();
        assertNull(player.current);
        assertFalse(player.playing);
    }

    @Test
    void openingChatPreemptsAnOldInteractionAndRejectsItsCompletion(){
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player));
        controller.play(AnimationType.TAP);
        Runnable obsolete=player.completions.getFirst();
        Runnable hide=controller.conversationOpened();
        obsolete.run();
        controller.play(AnimationType.TAP);
        controller.replyEmotion(SpeechEmotion.TEASING);
        assertEquals(1,player.completions.size());
        assertEquals(AnimationType.THINKING,player.current);
        hide.run();
        obsolete.run();
        assertEquals(AnimationType.IDLE,player.current);
    }

    @Test
    void explicitModesPreemptAnOldTapWithoutBeingResetByItsCompletion(){
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player));
        controller.play(AnimationType.TAP);
        Runnable obsolete=player.completions.getFirst();
        controller.play(AnimationType.WALK);
        assertEquals(AnimationType.WALK,player.current);
        obsolete.run();
        assertEquals(AnimationType.WALK,player.current);
        controller.play(AnimationType.SLEEP);
        assertEquals(AnimationType.SLEEP,player.current);
        obsolete.run();
        assertEquals(AnimationType.SLEEP,player.current);
    }

    @Test
    void idleThresholdActivityAndBackgroundSpeechUseTheCorrectSleepingBase(){
        var now=new AtomicLong();
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player),now::get,Duration.ofMinutes(2));
        controller.play(AnimationType.IDLE);
        now.set(Duration.ofMinutes(2).toNanos()-1);
        controller.checkIdle();
        assertEquals(AnimationType.IDLE,player.current);
        now.incrementAndGet(); controller.checkIdle();
        assertEquals(AnimationType.SLEEP,player.current);
        controller.setTalking(true);
        assertEquals(AnimationType.TALK,player.current);
        controller.setTalking(false);
        assertEquals(AnimationType.SLEEP,player.current);
        controller.userActivity();
        assertEquals(AnimationType.IDLE,player.current);
        now.addAndGet(Duration.ofSeconds(119).toNanos()); controller.checkIdle();
        assertEquals(AnimationType.IDLE,player.current);
        now.addAndGet(Duration.ofSeconds(1).toNanos()); controller.checkIdle();
        assertEquals(AnimationType.SLEEP,player.current);
    }

    @Test
    void pendingWorkPreventsSleepAndTheNativeWalkLoopYieldsToIdleSleepAndInteraction(){
        var now=new AtomicLong();
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player),now::get,Duration.ofMinutes(2));
        Runnable pending=controller.thinkingStarted();
        now.set(Duration.ofMinutes(3).toNanos()); controller.checkIdle();
        assertEquals(AnimationType.THINKING,player.current);
        pending.run();
        assertEquals(AnimationType.SLEEP,player.current);
        controller.play(AnimationType.WALK);
        assertEquals(CharacterState.WALKING,controller.state());
        assertEquals(AnimationType.WALK,player.current);
        now.addAndGet(Duration.ofMinutes(2).toNanos()); controller.checkIdle();
        assertEquals(AnimationType.SLEEP,player.current);
        controller.userActivity();
        assertEquals(AnimationType.IDLE,player.current);
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

    @Test
    void autonomousWalkingNeverPostponesLongIdleSleepOrWakesASleepingCharacter(){
        var now=new AtomicLong();
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player),now::get,Duration.ofMinutes(2));
        now.set(Duration.ofSeconds(110).toNanos());
        assertTrue(controller.setAutonomousWalking(true));
        assertEquals(AnimationType.WALK,player.current);
        controller.setAutonomousWalking(false);
        assertEquals(AnimationType.IDLE,player.current);
        now.set(Duration.ofMinutes(2).toNanos());
        assertFalse(controller.setAutonomousWalking(true));
        assertEquals(AnimationType.SLEEP,player.current);
        controller.setAutonomousWalking(false);
        assertEquals(AnimationType.SLEEP,player.current);
        controller.userActivity();
        assertTrue(controller.setAutonomousWalking(true));
    }

    @Test
    void autonomousWalkingCannotOverrideConversationSpeechOrAnInteraction(){
        var player=new Player();
        var controller=new CharacterController(new AnimationController(player));
        Runnable hidden=controller.conversationOpened();
        assertFalse(controller.setAutonomousWalking(true));
        assertEquals(AnimationType.THINKING,player.current);
        hidden.run();
        controller.setTalking(true);
        assertFalse(controller.setAutonomousWalking(true));
        assertEquals(AnimationType.TALK,player.current);
        controller.setTalking(false);
        controller.play(AnimationType.TAP);
        assertFalse(controller.setAutonomousWalking(true));
        assertEquals(AnimationType.TAP,player.current);
        player.completions.getFirst().run();
        assertEquals(AnimationType.IDLE,player.current);
        controller.close();
        assertFalse(controller.setAutonomousWalking(true));
    }
}
