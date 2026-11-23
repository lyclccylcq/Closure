package app.ui.pet;

import app.character.animation.AnimationPlayer;
import app.character.animation.AnimationType;
import app.character.animation.FrameAnimationPlayer;
import app.character.animation.spine.SpineAnimationPlayer;
import app.character.animation.spine.SpineModel;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;

public final class PetAnimationPanel extends JPanel {
    private final JLabel frameLabel;
    private final AnimationPlayer animationPlayer;
    private final String rendererName;

    public PetAnimationPanel(int width, int height, Icon fallbackIcon) {
        super(new BorderLayout());

        setOpaque(false);
        setPreferredSize(new Dimension(width, height));

        frameLabel = new JLabel(fallbackIcon);
        frameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        frameLabel.setVerticalAlignment(SwingConstants.CENTER);
        frameLabel.setOpaque(false);
        add(frameLabel, BorderLayout.CENTER);

        AnimationPlayer selected = null;
        if (!"frames".equals(System.getProperty("closure.animation", "spine"))) {
            try {
                String root = System.getProperty("closure.spine.resources", SpineModel.RESOURCE_ROOT);
                SpineModel model = root.equals(SpineModel.RESOURCE_ROOT) ? SpineModel.bundled() : SpineModel.load(root);
                selected = new SpineAnimationPlayer(frameLabel, width, height, model);
                System.out.println("角色渲染：Spine " + model.version() + " (CPU, evaluation)");
            } catch (RuntimeException | LinkageError failure) {
                System.err.println("Spine 加载失败，使用 PNG 动画：" + failure.getMessage());
            }
        }
        if (selected == null) selected = new FrameAnimationPlayer(frameLabel, width, height, fallbackIcon);
        animationPlayer = selected;
        rendererName = selected instanceof SpineAnimationPlayer ? "spine" : "frames";
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
        if (animationPlayer instanceof FrameAnimationPlayer frames) frames.play(action);
        else animationPlayer.play(AnimationType.GREETING);
    }

    public void dispose() {
        animationPlayer.close();
    }

    public String rendererName() { return rendererName; }
}
