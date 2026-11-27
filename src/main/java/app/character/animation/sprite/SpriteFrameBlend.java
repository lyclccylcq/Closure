package app.character.animation.sprite;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Interpolate premultiplied RGBA once; two SrcOver fades reduce shared coverage. */
public final class SpriteFrameBlend {
    private SpriteFrameBlend() { }

    public static BufferedImage mix(BufferedImage previous, BufferedImage current, float amount) {
        if (previous == null || amount >= 1) return current;
        if (amount <= 0) return previous;
        int width = current.getWidth(), height = current.getHeight();
        if (previous.getWidth() != width || previous.getHeight() != height || !Float.isFinite(amount)) {
            throw new IllegalArgumentException("Invalid transition frames");
        }
        int weight = Math.round(amount * 256), before = 256 - weight;
        int[] left = previous.getRGB(0, 0, width, height, null, 0, width);
        int[] right = current.getRGB(0, 0, width, height, null, 0, width);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] pixels = ((DataBufferInt) result.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++) {
            int a = left[i] >>> 24, b = right[i] >>> 24;
            int alpha = (a * before + b * weight + 128) >> 8;
            int red = channel(left[i] >> 16, a, right[i] >> 16, b, before, weight);
            int green = channel(left[i] >> 8, a, right[i] >> 8, b, before, weight);
            int blue = channel(left[i], a, right[i], b, before, weight);
            pixels[i] = alpha << 24 | red << 16 | green << 8 | blue;
        }
        return result;
    }

    private static int channel(int first, int alpha, int second, int otherAlpha, int before, int weight) {
        int a = ((first & 255) * alpha + 127) / 255;
        int b = ((second & 255) * otherAlpha + 127) / 255;
        return (a * before + b * weight + 128) >> 8;
    }
}
