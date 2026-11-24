package app.ui.pet;

import org.junit.jupiter.api.Test;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class PetAnimationPanelTest {
    @Test
    void facingMirrorsCompletedPixelsWithoutChangingTheSourceFrame() throws Exception {
        String old = System.getProperty("closure.animation");
        System.setProperty("closure.animation", "frames");
        try {
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage source = new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB);
                source.setRGB(3, 8, Color.RED.getRGB());
                source.setRGB(26, 15, Color.BLUE.getRGB());
                var panel = new PetAnimationPanel(32, 24, new ImageIcon(source));
                try {
                    panel.setSize(32, 24);
                    panel.doLayout();
                    BufferedImage right = paint(panel);
                    panel.setFacingLeft(true);
                    assertTrue(panel.isFacingLeft());
                    BufferedImage left = paint(panel);
                    for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++) {
                        assertEquals(right.getRGB(31 - x, y), left.getRGB(x, y));
                    }
                    assertEquals(Color.RED.getRGB(), source.getRGB(3, 8));
                    panel.setFacingLeft(false);
                    assertEquals(right.getRGB(3, 8), paint(panel).getRGB(3, 8));
                } finally { panel.dispose(); }
            });
        } finally {
            if (old == null) System.clearProperty("closure.animation"); else System.setProperty("closure.animation", old);
        }
    }

    private static BufferedImage paint(PetAnimationPanel panel) {
        var image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { panel.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }
}
