package app.character.animation.spine;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Orthographic textured triangles, PMA source-over/additive blending and a top-left fill rule. */
final class SoftwareRasterizer {
    private final int width;
    private final int height;
    private final int[] pixels;
    final BufferedImage image;

    SoftwareRasterizer(int width, int height) {
        this.width = width;
        this.height = height;
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    private static double edge(double ax, double ay, double bx, double by, double x, double y) {
        return (bx - ax) * (y - ay) - (by - ay) * (x - ax);
    }

    private static boolean topLeft(double ax, double ay, double bx, double by) {
        return by < ay || (by == ay && bx > ax);
    }

    void triangle(float[] xy, float[] uv, int a, int b, int c, int[] texture, int tw, int th,
                  float red, float green, float blue, float alpha, boolean additive) {
        double area = edge(xy[a], xy[a + 1], xy[b], xy[b + 1], xy[c], xy[c + 1]);
        if (Math.abs(area) < 1e-8) return;
        if (area < 0) { int swap = b; b = c; c = swap; area = -area; }
        int minX = Math.max(0, (int) Math.floor(Math.min(xy[a], Math.min(xy[b], xy[c]))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(xy[a], Math.max(xy[b], xy[c]))) - 1);
        int minY = Math.max(0, (int) Math.floor(Math.min(xy[a + 1], Math.min(xy[b + 1], xy[c + 1]))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(xy[a + 1], Math.max(xy[b + 1], xy[c + 1]))) - 1);
        boolean ab = topLeft(xy[a], xy[a + 1], xy[b], xy[b + 1]);
        boolean bc = topLeft(xy[b], xy[b + 1], xy[c], xy[c + 1]);
        boolean ca = topLeft(xy[c], xy[c + 1], xy[a], xy[a + 1]);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double wa = edge(xy[b], xy[b + 1], xy[c], xy[c + 1], x + .5, y + .5);
                double wb = edge(xy[c], xy[c + 1], xy[a], xy[a + 1], x + .5, y + .5);
                double wc = edge(xy[a], xy[a + 1], xy[b], xy[b + 1], x + .5, y + .5);
                if (wa < 0 || (wa == 0 && !bc) || wb < 0 || (wb == 0 && !ca) || wc < 0 || (wc == 0 && !ab)) continue;
                double u = (wa * uv[a] + wb * uv[b] + wc * uv[c]) / area - .5;
                double v = (wa * uv[a + 1] + wb * uv[b + 1] + wc * uv[c + 1]) / area - .5;
                int sample = sample(texture, tw, th, u, v);
                int sa = Math.min(255, Math.round((sample >>> 24) * alpha));
                if (sa == 0) continue;
                int sr = Math.min(sa, Math.round(((sample >>> 16) & 255) * red * alpha));
                int sg = Math.min(sa, Math.round(((sample >>> 8) & 255) * green * alpha));
                int sb = Math.min(sa, Math.round((sample & 255) * blue * alpha));
                int dest = pixels[y * width + x];
                int factor = additive ? 255 : 255 - sa;
                int da = Math.min(255, sa + ((dest >>> 24) * factor + 127) / 255);
                int dr = Math.min(255, sr + (((dest >>> 16) & 255) * factor + 127) / 255);
                int dg = Math.min(255, sg + (((dest >>> 8) & 255) * factor + 127) / 255);
                int db = Math.min(255, sb + ((dest & 255) * factor + 127) / 255);
                pixels[y * width + x] = (da << 24) | (dr << 16) | (dg << 8) | db;
            }
        }
    }

    private static int sample(int[] texture, int width, int height, double u, double v) {
        u = Math.max(0, Math.min(width - 1, u));
        v = Math.max(0, Math.min(height - 1, v));
        int x = (int) u, y = (int) v;
        int x2 = Math.min(x + 1, width - 1), y2 = Math.min(y + 1, height - 1);
        double dx = u - x, dy = v - y;
        int p = texture[y * width + x], q = texture[y * width + x2];
        int r = texture[y2 * width + x], s = texture[y2 * width + x2];
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            double upper = ((p >>> shift) & 255) * (1 - dx) + ((q >>> shift) & 255) * dx;
            double lower = ((r >>> shift) & 255) * (1 - dx) + ((s >>> shift) & 255) * dx;
            result |= ((int) Math.round(upper * (1 - dy) + lower * dy)) << shift;
        }
        return result;
    }
}
