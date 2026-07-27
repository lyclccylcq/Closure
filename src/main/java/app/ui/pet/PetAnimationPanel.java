package app.ui.pet;

import javax.swing.*;
import java.awt.Image;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class PetAnimationPanel extends JPanel {
    private final JLabel frameLabel;
    private final Icon fallbackIcon;
    private final int targetWidth;
    private final int targetHeight;
    private final Map<PetAction, List<Icon>> frameCache = new EnumMap<>(PetAction.class);
    private Timer animationTimer;
    private PetAction currentAction;
    private int frameIndex;

    public PetAnimationPanel(PetAction initialAction, int width, int height, Icon fallbackIcon) {
        super(new BorderLayout());
        this.fallbackIcon = fallbackIcon;
        targetWidth = width;
        targetHeight = height;

        setOpaque(false);
        setPreferredSize(new Dimension(width, height));

        frameLabel = new JLabel(fallbackIcon);
        frameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        frameLabel.setVerticalAlignment(SwingConstants.CENTER);
        frameLabel.setOpaque(false);
        add(frameLabel, BorderLayout.CENTER);

        play(initialAction);
    }

    public void play(PetAction action) {
        List<Icon> frames = framesFor(action);
        currentAction = action;
        frameIndex = 0;

        if (animationTimer != null) {
            animationTimer.stop();
        }

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
            URL frameUrl = PetAnimationPanel.class.getResource(action.framePath(frameNumber));
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

    public void dispose() {
        if (animationTimer != null) {
            animationTimer.stop();
        }
    }
}
