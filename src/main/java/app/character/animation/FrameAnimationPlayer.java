package app.character.animation;

import app.ui.pet.PetAction;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Image;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FrameAnimationPlayer implements AnimationPlayer {
    private final JLabel frameLabel;
    private final Icon fallbackIcon;
    private final int targetWidth;
    private final int targetHeight;
    private final Map<PetAction, List<Icon>> frameCache = new EnumMap<>(PetAction.class);

    private Timer animationTimer;
    private PetAction currentAction;
    private int frameIndex;

    public FrameAnimationPlayer(JLabel frameLabel, int targetWidth, int targetHeight, Icon fallbackIcon) {
        this.frameLabel = Objects.requireNonNull(frameLabel, "frameLabel");
        this.fallbackIcon = fallbackIcon;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
    }

    @Override
    public void play(AnimationType animation) {
        Objects.requireNonNull(animation, "animation");
        play(actionFor(animation));
    }

    public void play(PetAction action) {
        Objects.requireNonNull(action, "action");
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> play(action));
            return;
        }

        playFrames(action);
    }

    @Override
    public void stop() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::stop);
            return;
        }

        stopTimer();
    }

    @Override
    public boolean isPlaying() {
        return animationTimer != null && animationTimer.isRunning();
    }

    private PetAction actionFor(AnimationType animation) {
        return switch (animation) {
            case IDLE, TALK, TAP, GREETING, HAPPY, THINKING, SLEEP -> PetAction.OPEN;
        };
    }

    private void playFrames(PetAction action) {
        if (action == currentAction && isPlaying()) {
            return;
        }

        List<Icon> frames = framesFor(action);
        currentAction = action;
        frameIndex = 0;

        stopTimer();

        if (frames.isEmpty()) {
            frameLabel.setIcon(fallbackIcon);
            return;
        }

        frameLabel.setIcon(frames.getFirst());
        animationTimer = new Timer(action.frameDelayMillis(), event -> showNextFrame());
        animationTimer.start();
    }

    private List<Icon> framesFor(PetAction action) {
        return frameCache.computeIfAbsent(action, this::loadFrames);
    }

    private List<Icon> loadFrames(PetAction action) {
        List<Icon> loadedFrames = new ArrayList<>();

        for (int frameNumber = 1; ; frameNumber++) {
            URL frameUrl = FrameAnimationPlayer.class.getResource(action.framePath(frameNumber));
            if (frameUrl == null) {
                break;
            }

            ImageIcon originalIcon = new ImageIcon(frameUrl);
            if (originalIcon.getIconWidth() == targetWidth && originalIcon.getIconHeight() == targetHeight) {
                loadedFrames.add(originalIcon);
                continue;
            }

            Image scaledImage = originalIcon.getImage().getScaledInstance(
                    targetWidth,
                    targetHeight,
                    Image.SCALE_SMOOTH
            );
            loadedFrames.add(new ImageIcon(scaledImage));
        }

        if (loadedFrames.isEmpty()) {
            System.out.println("模型 PNG 帧不存在：" + action.frameDirectory());
        }

        return loadedFrames;
    }

    private void showNextFrame() {
        List<Icon> frames = framesFor(currentAction);
        if (frames.isEmpty()) {
            return;
        }

        frameIndex = (frameIndex + 1) % frames.size();
        frameLabel.setIcon(frames.get(frameIndex));
    }

    private void stopTimer() {
        if (animationTimer != null) {
            animationTimer.stop();
            animationTimer = null;
        }
    }
}
