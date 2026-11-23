package app.character;

import app.character.animation.AnimationController;
import app.character.animation.AnimationType;
import app.voice.model.SpeechEmotion;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the persistent requested state separate from higher-priority one-shot actions. */
public final class CharacterController implements CharacterActivityListener, AutoCloseable {
    private final AnimationController animations;
    private final Set<Long> thinking = new HashSet<>();
    private long nextRequest;
    private long generation;
    private boolean talking;
    private boolean sleeping;
    private boolean closed;
    private AnimationType interaction;

    public CharacterController(AnimationController animations) {
        this.animations = Objects.requireNonNull(animations, "animations");
    }

    public synchronized void play(AnimationType animation) {
        if (closed) return;
        switch (Objects.requireNonNull(animation)) {
            case TAP, GREETING, HAPPY -> interact(animation);
            case TALK -> setTalking(true);
            case SLEEP -> { sleeping = true; refresh(); }
            case IDLE -> { sleeping = false; refresh(); }
            case THINKING -> throw new IllegalArgumentException("Use a paired thinkingStarted completion");
        }
    }

    public synchronized void setTalking(boolean active) {
        if (closed) return;
        talking = active;
        if (active) sleeping = false;
        refresh();
    }

    @Override
    public synchronized Runnable thinkingStarted() {
        if (closed) return () -> { };
        long request = ++nextRequest;
        thinking.add(request);
        sleeping = false;
        refresh();
        AtomicBoolean finished = new AtomicBoolean();
        return () -> {
            if (!finished.compareAndSet(false, true)) return;
            synchronized (CharacterController.this) {
                thinking.remove(request);
                if (!closed) refresh();
            }
        };
    }

    @Override
    public synchronized void replyEmotion(SpeechEmotion emotion) {
        if (!closed && (emotion == SpeechEmotion.CHEERFUL || emotion == SpeechEmotion.TEASING)) interact(AnimationType.HAPPY);
    }

    public synchronized CharacterState state() {
        if (interaction != null) return CharacterState.INTERACTING;
        if (talking) return CharacterState.TALKING;
        if (!thinking.isEmpty()) return CharacterState.THINKING;
        return sleeping ? CharacterState.SLEEPING : CharacterState.IDLE;
    }

    private static int priority(AnimationType type) {
        return type == AnimationType.TAP ? 3 : type == AnimationType.GREETING ? 2 : 1;
    }

    private void interact(AnimationType type) {
        if (interaction != null && priority(interaction) > priority(type)) return;
        interaction = type;
        long token = ++generation;
        animations.playOnce(type, () -> {
            synchronized (CharacterController.this) {
                if (closed || token != generation) return;
                interaction = null;
                refresh();
            }
        });
    }

    private void refresh() {
        if (interaction != null || closed) return;
        AnimationType next = talking ? AnimationType.TALK : !thinking.isEmpty() ? AnimationType.THINKING
                : sleeping ? AnimationType.SLEEP : AnimationType.IDLE;
        if (animations.currentAnimation() != next || !animations.isPlaying()) animations.play(next);
    }

    @Override
    public synchronized void close() {
        closed = true;
        generation++;
        interaction = null;
        thinking.clear();
        talking = false;
        sleeping = false;
        animations.stop();
    }
}
