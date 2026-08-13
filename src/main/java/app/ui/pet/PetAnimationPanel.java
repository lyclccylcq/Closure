package app.ui.pet;

import app.character.animation.AnimationPlayer;
import app.character.animation.AnimationType;
import app.character.animation.FrameAnimationPlayer;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;

public final class PetAnimationPanel extends JPanel {
    private final JLabel frameLabel;
    private final FrameAnimationPlayer animationPlayer;

    public PetAnimationPanel(int width, int height, Icon fallbackIcon) {
        super(new BorderLayout());

        setOpaque(false);
        setPreferredSize(new Dimension(width, height));

        frameLabel = new JLabel(fallbackIcon);
        frameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        frameLabel.setVerticalAlignment(SwingConstants.CENTER);
        frameLabel.setOpaque(false);
        add(frameLabel, BorderLayout.CENTER);

        animationPlayer = new FrameAnimationPlayer(frameLabel, width, height, fallbackIcon);
    }

    public PetAnimationPanel(AnimationType initialAnimation, int width, int height, Icon fallbackIcon) {
        this(width, height, fallbackIcon);
        play(initialAnimation);
    }

    public PetAnimationPanel(PetAction initialAction, int width, int height, Icon fallbackIcon) {
        this(width, height, fallbackIcon);
        play(initialAction);
    }

    public AnimationPlayer animationPlayer() {
        return animationPlayer;
    }

    public void play(AnimationType animation) {
        animationPlayer.play(animation);
    }

    public void play(PetAction action) {
        animationPlayer.play(action);
    }

    public void dispose() {
        animationPlayer.stop();
    }
}
