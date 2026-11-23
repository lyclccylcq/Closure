package app.ui.pet;

import org.junit.jupiter.api.Test;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class TranslucentWindowPainterTest {
    @Test
    void thePreviousFrameRemainsVisibleUntilTheEntireNextSceneIsReady() {
        var painter = new TranslucentWindowPainter();
        BufferedImage front = image(8, 8);
        Graphics2D destination = front.createGraphics();
        try {
            destination.setColor(Color.RED);
            destination.fillRect(0, 0, 8, 8);
            painter.paint(destination, 8, 8, scene -> {
                scene.setComposite(AlphaComposite.Clear);
                scene.fillRect(0, 0, 8, 8);
                assertEquals(Color.RED.getRGB(), front.getRGB(4, 4), "Window clearing must stay offscreen");
                scene.setComposite(AlphaComposite.SrcOver);
                scene.setColor(Color.BLUE);
                scene.fillRect(3, 3, 2, 2);
                assertEquals(Color.RED.getRGB(), front.getRGB(4, 4), "Partly painted scenes must stay offscreen");
            });
            assertEquals(Color.BLUE.getRGB(), front.getRGB(4, 4));
            assertEquals(0, front.getRGB(0, 0), "A previous pose must not leave a trail");
        } finally { destination.dispose(); }
    }

    @Test
    void repeatedTranslucentFramesReplaceRatherThanAccumulateAndResizeCleanly() {
        var painter = new TranslucentWindowPainter();
        for (int size : new int[]{8, 8, 13, 5}) {
            BufferedImage front = image(size, size);
            Graphics2D destination = front.createGraphics();
            try {
                for (int frame = 0; frame < 30; frame++) {
                    int x = frame % 2;
                    painter.paint(destination, size, size, scene -> {
                        scene.setColor(new Color(40, 120, 220, 128));
                        scene.fillRect(x, 1, 1, 1);
                    });
                    assertEquals(128, front.getRGB(x, 1) >>> 24);
                    assertEquals(0, front.getRGB(1 - x, 1));
                    assertEquals(0, front.getRGB(size - 1, size - 1));
                }
            } finally { destination.dispose(); }
        }
    }

    @Test
    void hiDpiClippingAndCallerGraphicsStateArePreserved() {
        var painter = new TranslucentWindowPainter();
        BufferedImage front = image(12, 8);
        Graphics2D destination = front.createGraphics();
        try {
            destination.setColor(Color.BLUE);
            destination.fillRect(0, 0, 12, 8);
            destination.scale(2, 2);
            destination.setClip(new Rectangle(1, 1, 3, 2));
            var composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f);
            destination.setComposite(composite);
            var transform = destination.getTransform();
            var clip = destination.getClipBounds();
            painter.paint(destination, 6, 4, scene -> {
                assertEquals(2, scene.getTransform().getScaleX());
                assertEquals(2, scene.getTransform().getScaleY());
                scene.setColor(Color.RED);
                scene.fillRect(0, 0, 6, 4);
            });
            assertEquals(128, front.getRGB(2, 2) >>> 24);
            assertEquals(Color.BLUE.getRGB(), front.getRGB(1, 2));
            assertEquals(Color.BLUE.getRGB(), front.getRGB(8, 2));
            assertEquals(composite, destination.getComposite());
            assertEquals(transform, destination.getTransform());
            assertEquals(clip, destination.getClipBounds());
        } finally { destination.dispose(); }
    }

    @Test
    void failedPreparationKeepsTheDisplayedFrameAndDoesNotPolluteTheNextPaint() {
        var painter = new TranslucentWindowPainter();
        BufferedImage front = image(8, 8);
        Graphics2D destination = front.createGraphics();
        try {
            destination.setColor(Color.GREEN);
            destination.fillRect(0, 0, 8, 8);
            assertThrows(IllegalStateException.class, () -> painter.paint(destination, 8, 8, scene -> {
                scene.setColor(Color.RED);
                scene.fillRect(0, 0, 8, 8);
                throw new IllegalStateException("Incomplete scene");
            }));
            assertEquals(Color.GREEN.getRGB(), front.getRGB(0, 0));
            assertEquals(Color.GREEN.getRGB(), front.getRGB(7, 7));
            painter.paint(destination, 8, 8, scene -> { });
            assertEquals(0, front.getRGB(0, 0));
            assertEquals(0, front.getRGB(7, 7));
        } finally { destination.dispose(); }
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
    }
}
