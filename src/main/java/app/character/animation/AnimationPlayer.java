package app.character.animation;

public interface AnimationPlayer extends AutoCloseable {
    void play(AnimationType animation);

    /** Implementations supporting one-shots invoke completion after the final frame. */
    default void playOnce(AnimationType animation, Runnable completion) {
        play(animation);
    }

    void stop();

    boolean isPlaying();

    @Override
    default void close() { stop(); }
}
