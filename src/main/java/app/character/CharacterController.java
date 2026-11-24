package app.character;

import app.character.animation.AnimationController;
import app.character.animation.AnimationType;
import app.voice.model.SpeechEmotion;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Persistent conversation/idle state is independent of transient requests and speech. */
public final class CharacterController implements CharacterActivityListener, AutoCloseable {
    public static final Duration DEFAULT_IDLE_SLEEP_DELAY=Duration.ofMinutes(2);
    private final AnimationController animations;
    private final LongSupplier clock;
    private final long idleSleepNanos;
    private final Set<Long> thinking=new HashSet<>();
    private final Set<Long> conversations=new HashSet<>();
    private long nextRequest;
    private long generation;
    private long lastActivity;
    private boolean talking;
    private boolean sleeping;
    private boolean walking;
    private boolean closed;
    private AnimationType interaction;

    public CharacterController(AnimationController animations){
        this(animations,System::nanoTime,DEFAULT_IDLE_SLEEP_DELAY);
    }

    public CharacterController(AnimationController animations,LongSupplier clock,Duration idleSleepDelay){
        this.animations=Objects.requireNonNull(animations,"animations");
        this.clock=Objects.requireNonNull(clock,"clock");
        Objects.requireNonNull(idleSleepDelay,"idleSleepDelay");
        if(idleSleepDelay.isZero() || idleSleepDelay.isNegative()){
            throw new IllegalArgumentException("Idle sleep delay must be positive");
        }
        idleSleepNanos=idleSleepDelay.toNanos();
        lastActivity=clock.getAsLong();
    }

    public synchronized void play(AnimationType animation){
        if(closed) return;
        switch(Objects.requireNonNull(animation)){
            case TAP, GREETING, HAPPY -> { recordActivity(); interact(animation); }
            case TALK -> setTalking(true);
            case WALK -> { recordActivity(); cancelInteraction(); walking=true; refresh(); }
            case SLEEP -> { cancelInteraction(); sleeping=true; walking=false; refresh(); }
            case IDLE -> { recordActivity(); cancelInteraction(); refresh(); }
            case THINKING, THINKING_TALK -> throw new IllegalArgumentException("Use paired activity hooks");
        }
    }

    /** Real pet interaction wakes it; automatic bubbles and background speech do not reset this clock. */
    public synchronized void userActivity(){
        if(closed) return;
        recordActivity();
        refresh();
    }

    /** Called by the normal window's bounded timer; time itself is injectable in tests. */
    public synchronized void checkIdle(){
        if(!closed) refresh();
    }

    /** Autonomous movement is not user activity and must never postpone sleep or interrupt a task. */
    public synchronized boolean setAutonomousWalking(boolean active){
        if(closed) return false;
        CharacterState current=state();
        walking=active && (current==CharacterState.IDLE || current==CharacterState.WALKING);
        refresh();
        return walking && baseAnimation()==AnimationType.WALK;
    }

    public synchronized void setTalking(boolean active){
        if(closed) return;
        talking=active;
        refresh();
    }

    @Override
    public synchronized Runnable conversationOpened(){
        if(closed) return () -> { };
        long id=++nextRequest;
        conversations.add(id);
        recordActivity();
        // The visible conversation pose also preempts an old one-shot and its callback.
        cancelInteraction();
        refresh();
        AtomicBoolean finished=new AtomicBoolean();
        return () -> {
            if(!finished.compareAndSet(false,true)) return;
            synchronized(CharacterController.this){
                conversations.remove(id);
                if(!closed){
                    if(conversations.isEmpty()) recordActivity();
                    refresh();
                }
            }
        };
    }

    @Override
    public synchronized Runnable thinkingStarted(){
        if(closed) return () -> { };
        long request=++nextRequest;
        thinking.add(request);
        recordActivity();
        refresh();
        AtomicBoolean finished=new AtomicBoolean();
        return () -> {
            if(!finished.compareAndSet(false,true)) return;
            synchronized(CharacterController.this){
                thinking.remove(request);
                if(!closed) refresh();
            }
        };
    }

    @Override
    public synchronized void replyEmotion(SpeechEmotion emotion){
        if(!closed && (emotion==SpeechEmotion.CHEERFUL || emotion==SpeechEmotion.TEASING)){
            interact(AnimationType.HAPPY);
        }
    }

    public synchronized CharacterState state(){
        if(closed) return CharacterState.IDLE;
        if(interaction!=null) return CharacterState.INTERACTING;
        return switch(baseAnimation()){
            case TALK, THINKING_TALK -> CharacterState.TALKING;
            case THINKING -> CharacterState.THINKING;
            case SLEEP -> CharacterState.SLEEPING;
            case WALK -> CharacterState.WALKING;
            default -> CharacterState.IDLE;
        };
    }

    private void recordActivity(){
        lastActivity=clock.getAsLong();
        sleeping=false;
        walking=false;
    }

    private static int priority(AnimationType type){
        return type==AnimationType.TAP ? 3 : type==AnimationType.GREETING ? 2 : 1;
    }

    private void cancelInteraction(){
        interaction=null;
        generation++;
    }

    private void interact(AnimationType type){
        if(!conversations.isEmpty()){
            refresh();
            return;
        }
        if(interaction!=null && priority(interaction)>priority(type)) return;
        interaction=type;
        long token=++generation;
        animations.playOnce(type,() -> {
            synchronized(CharacterController.this){
                if(closed || token!=generation) return;
                interaction=null;
                refresh();
            }
        });
    }

    private AnimationType baseAnimation(){
        if(!conversations.isEmpty()) return talking ? AnimationType.THINKING_TALK : AnimationType.THINKING;
        if(talking) return AnimationType.TALK;
        if(!thinking.isEmpty()) return AnimationType.THINKING;
        if(sleeping || clock.getAsLong()-lastActivity>=idleSleepNanos) return AnimationType.SLEEP;
        return walking ? AnimationType.WALK : AnimationType.IDLE;
    }

    private void refresh(){
        if(interaction!=null || closed) return;
        AnimationType next=baseAnimation();
        if(animations.currentAnimation()!=next || !animations.isPlaying()) animations.play(next);
    }

    @Override
    public synchronized void close(){
        if(closed) return;
        closed=true;
        generation++;
        interaction=null;
        thinking.clear();
        conversations.clear();
        talking=false;
        sleeping=false;
        walking=false;
        animations.stop();
    }
}
