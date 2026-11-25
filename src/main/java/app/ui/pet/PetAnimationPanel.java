package app.ui.pet;

import app.character.animation.AnimationPlayer;
import app.character.animation.AnimationType;
import app.character.animation.FrameAnimationPlayer;
import app.character.animation.spine.SpineAnimationPlayer;
import app.character.animation.spine.SpineModel;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

public final class PetAnimationPanel extends JPanel {
    private final JLabel frameLabel;
    private final AnimationPlayer animationPlayer;
    private final String rendererName;
    private boolean facingLeft;

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
                System.out.println("角色渲染：Spine " + model.version() + " (CPU)");
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

    public boolean isFacingLeft() { return facingLeft; }

    public void setFacingLeft(boolean facingLeft) {
        if (this.facingLeft == facingLeft) return;
        this.facingLeft = facingLeft;
        repaint();
    }

    @Override
    protected void paintChildren(Graphics graphics) {
        if (!facingLeft) {
            super.paintChildren(graphics);
            return;
        }
        Graphics2D mirrored = (Graphics2D) graphics.create();
        try {
            mirrored.translate(getWidth(), 0);
            mirrored.scale(-1, 1);
            super.paintChildren(mirrored);
        } finally {
            mirrored.dispose();
        }
    }
}
