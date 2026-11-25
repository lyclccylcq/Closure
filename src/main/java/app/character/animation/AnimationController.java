package app.character.animation;

import java.util.Objects;

/**
 * 业务层统一通过这里表达“角色现在应该做什么”。
 */
public final class AnimationController {
    private final AnimationPlayer player;
    private AnimationType currentAnimation;

    public AnimationController(AnimationPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    public synchronized void play(AnimationType animation) {
        Objects.requireNonNull(animation, "animation");
        currentAnimation = animation;
        player.play(animation);
    }

    public synchronized void playOnce(AnimationType animation, Runnable completion) {
        currentAnimation = Objects.requireNonNull(animation, "animation");
        player.playOnce(animation, Objects.requireNonNull(completion, "completion"));
    }

    public synchronized void stop() {
        player.stop();
        currentAnimation = null;
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public void setDragMotion(float horizontal) {
        player.setDragMotion(Float.isFinite(horizontal) ? Math.max(-1, Math.min(1, horizontal)) : 0);
    }

    public synchronized AnimationType currentAnimation() {
        return currentAnimation;
    }
}
