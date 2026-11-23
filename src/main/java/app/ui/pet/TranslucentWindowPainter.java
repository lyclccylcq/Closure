package app.ui.pet;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.function.Consumer;

/** Keeps AWT's transparent-window clearing off the displayed surface. */
public final class TranslucentWindowPainter {
    private BufferedImage buffer;

    public synchronized void paint(Graphics2D destination, int width, int height, Consumer<Graphics2D> scene) {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(scene);
        if (width <= 0 || height <= 0) return;
        var transform = destination.getTransform();
        double scaleX = Math.hypot(transform.getScaleX(), transform.getShearY());
        double scaleY = Math.hypot(transform.getShearX(), transform.getScaleY());
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0 || scaleY <= 0) return;
        int pixelWidth = (int) Math.ceil(width * scaleX);
        int pixelHeight = (int) Math.ceil(height * scaleY);
        if (buffer == null || buffer.getWidth() != pixelWidth || buffer.getHeight() != pixelHeight) {
            buffer = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB_PRE);
        }

        Graphics2D frame = buffer.createGraphics();
        try {
            frame.setComposite(AlphaComposite.Clear);
            frame.fillRect(0, 0, pixelWidth, pixelHeight);
            frame.setComposite(destination.getComposite());
            frame.setRenderingHints(destination.getRenderingHints());
            frame.setPaint(destination.getPaint());
            frame.setFont(destination.getFont());
            frame.setStroke(destination.getStroke());
            frame.setBackground(destination.getBackground());
            frame.scale(scaleX, scaleY);
            frame.setClip(0, 0, width, height);
            scene.accept(frame);
        } finally {
            frame.dispose();
        }

        // Publish only after the entire scene succeeds. SRC replaces old transparent pixels too.
        Graphics2D output = (Graphics2D) destination.create();
        try {
            output.setComposite(AlphaComposite.Src);
            output.drawImage(buffer, 0, 0, width, height, null);
        } finally {
            output.dispose();
        }
    }
}
