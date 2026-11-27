package app.character.animation.sprite;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class SpriteFrameBlendTest {
    @Test void sharedCoverageStaysConstantAcrossEveryTransitionWeight() {
        var before = pixel(0xffed1738); var after = pixel(0xff3874ed);
        var translucent = pixel(0x801060ef);
        for (int i = 0; i <= 256; i++) {
            float amount = i / 256f;
            assertEquals(255, SpriteFrameBlend.mix(before, after, amount).getRGB(0, 0) >>> 24);
            assertEquals(128, SpriteFrameBlend.mix(translucent, translucent, amount).getRGB(0, 0) >>> 24);
        }
    }
    @Test void transparentPixelsNeverAddBlackHalosOrColour() {
        var visible = pixel(0x80ff0000); var transparent = pixel(0x0000ff00);
        int middle = SpriteFrameBlend.mix(visible, transparent, .5f).getRGB(0, 0);
        assertEquals(64, middle >>> 24); assertEquals(255, middle >> 16 & 255);
        assertEquals(0, middle & 0xffff);
        assertSame(visible, SpriteFrameBlend.mix(null, visible, 0));
    }
    @Test void interruptionStartsFromTheActuallyDisplayedComposite() {
        BufferedImage visible = SpriteFrameBlend.mix(pixel(0xff0000ff), pixel(0xffff0000), .5f);
        assertSame(visible, SpriteFrameBlend.mix(visible, pixel(0xff00ff00), 0));
        for (int i = 0; i < 50; i++) {
            visible = SpriteFrameBlend.mix(visible, pixel(i % 2 == 0 ? 0xff0000ff : 0xffff0000), .2f);
            assertEquals(255, visible.getRGB(0, 0) >>> 24);
        }
    }
    private static BufferedImage pixel(int colour) {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, colour); return image;
    }
}
