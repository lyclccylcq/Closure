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

    public void play(AnimationType animation) {
        Objects.requireNonNull(animation, "animation");
        player.play(animation);
        currentAnimation = animation;
    }

    public void stop() {
        player.stop();
        currentAnimation = null;
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public AnimationType currentAnimation() {
        return currentAnimation;
    }
}
