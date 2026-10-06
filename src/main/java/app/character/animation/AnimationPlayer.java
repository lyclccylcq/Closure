package app.character.animation;

public interface AnimationPlayer {
    void play(AnimationType animation);

    void stop();

    boolean isPlaying();
}
