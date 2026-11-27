package app.character.animation;

public interface AnimationPlayer extends AutoCloseable {
    void play(AnimationType animation);

    /** Implementations supporting one-shots invoke completion after the final frame. */
    default void playOnce(AnimationType animation, Runnable completion) {
        play(animation);
    }

    void stop();

    boolean isPlaying();

    /** Authored locomotion is optional for prerecorded character skins. */
    default boolean supportsWalking() { return true; }

    /** Bounded horizontal pointer motion; renderers without procedural bones can ignore it. */
    default void setDragMotion(float horizontal) { }

    @Override
    default void close() { stop(); }
}
