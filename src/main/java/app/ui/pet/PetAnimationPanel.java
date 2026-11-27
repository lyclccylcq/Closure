package app.ui.pet;

import app.character.animation.AnimationPlayer;
import app.character.animation.AnimationType;
import app.character.animation.FrameAnimationPlayer;
import app.character.animation.spine.SpineAnimationPlayer;
import app.character.animation.spine.SpineModel;
import app.character.animation.sprite.SpriteAnimationPlayer;
import app.character.animation.sprite.SpriteModel;
import app.ui.PetWindow;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
        String renderer = System.getProperty("closure.animation", "sprite");
        if ("sprite".equals(renderer)) {
            try {
                selected = new SpriteAnimationPlayer(frameLabel, width, height, SpriteModel.local());
            } catch (RuntimeException failure) {
                System.err.println("新版模型不可用，临时显示原帧素材。请按 docs/character/new-model.md 导入：" + failure.getMessage());
            }
        } else if ("spine".equals(renderer)) {
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
        rendererName = selected instanceof SpriteAnimationPlayer ? "sprite"
                : selected instanceof SpineAnimationPlayer ? "spine" : "frames";
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
    public boolean supportsWalking() { return animationPlayer.supportsWalking(); }

    /** Single-renderer frame feed, observed only while the workbench is visible. Call on the EDT. */
    public Runnable observeFrames(Consumer<BufferedImage> observer) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Observe frames on EDT");
        AtomicBoolean active = new AtomicBoolean(true);
        Runnable emit = () -> {
            if (!active.get()) return;
            Icon icon = frameLabel.getIcon();
            if (icon == null) return;
            int width = icon.getIconWidth(), height = icon.getIconHeight();
            BufferedImage frame = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D graphics = frame.createGraphics();
            try {
                graphics.scale(2, 2);
                if (facingLeft) { graphics.translate(width, 0); graphics.scale(-1, 1); }
                icon.paintIcon(frameLabel, graphics, 0, 0);
            } finally { graphics.dispose(); }
            observer.accept(frame);
        };
        PropertyChangeListener changed = event -> emit.run();
        frameLabel.addPropertyChangeListener("icon", changed);
        addPropertyChangeListener("facingLeft", changed);
        emit.run();
        return () -> {
            if (!active.getAndSet(false)) return;
            Runnable detach = () -> {
                frameLabel.removePropertyChangeListener("icon", changed);
                removePropertyChangeListener("facingLeft", changed);
            };
            if (SwingUtilities.isEventDispatchThread()) detach.run(); else SwingUtilities.invokeLater(detach);
        };
    }

    @Override protected boolean isPaintingOrigin() { return true; }

    @Override public void paintImmediately(int x, int y, int width, int height) {
        java.awt.Window window = SwingUtilities.getWindowAncestor(this);
        if (window instanceof PetWindow && !window.isOpaque()) window.repaint();
        else super.paintImmediately(x, y, width, height);
    }

    public boolean isFacingLeft() { return facingLeft; }

    public void setFacingLeft(boolean facingLeft) {
        if (this.facingLeft == facingLeft) return;
        boolean previous = this.facingLeft;
        this.facingLeft = facingLeft;
        firePropertyChange("facingLeft", previous, facingLeft);
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
