package app.character.animation.spine;

import app.character.animation.AnimationType;
import com.esotericsoftware.spine.*;
import com.esotericsoftware.spine.Animation.MixBlend;
import com.esotericsoftware.spine.Animation.MixDirection;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Map;

/** One mutable pose per renderer. Call from a single rendering thread. */
public final class SpineRenderer {
    public record Bounds(float minX, float minY, float maxX, float maxY) {
        public float width() { return maxX - minX; }
        public float height() { return maxY - minY; }
    }
    private final SpineModel model;
    private final Skeleton skeleton;
    private final Bounds bounds;

    public SpineRenderer(SpineModel model) {
        this.model = model;
        synchronized (model) {
            // libGDX's data Arrays reuse iterators; serialize skeleton construction as well as sampling.
            skeleton = new Skeleton(model.data);
            if (model.cameraBounds == null) model.cameraBounds = measureBounds();
            bounds = model.cameraBounds;
        }
    }

    public static String sourceAction(AnimationType type) {
        return switch (type) {
            case IDLE, TALK -> "Relax";
            case TAP, GREETING, HAPPY -> "Interact";
            case THINKING -> "Sit";
            case SLEEP -> "Sleep";
        };
    }

    public float duration(AnimationType type) { return model.animations().get(sourceAction(type)); }
    public Bounds bounds() { return bounds; }
    public Map<String, Float> animations() { return model.animations(); }

    public BufferedImage render(AnimationType type, float seconds, int width, int height) {
        return render(sourceAction(type), seconds, type, width, height);
    }

    public BufferedImage render(String action, float seconds, AnimationType variant, int width, int height) {
        if (width < 32 || height < 32 || width > 1024 || height > 1024) throw new IllegalArgumentException("Invalid render size");
        pose(action, seconds, variant);
        int factor = width <= 512 && height <= 512 ? 2 : 1;
        BufferedImage rendered = renderCurrentPose(bounds, width * factor, height * factor);
        if (factor == 1) return rendered;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(rendered, 0, 0, width, height, null);
        graphics.dispose();
        return image;
    }

    private BufferedImage renderCurrentPose(Bounds camera, int width, int height) {
        var raster = new SoftwareRasterizer(width, height);
        float scale = Math.min((width - 20f) / camera.width(), (height - 20f) / camera.height());
        float xOffset = (width - camera.width() * scale) / 2;
        float yOffset = (height - camera.height() * scale) / 2;
        SpineGeometry.visit(model, skeleton, part -> {
            float[] xy = part.vertices();
            for (int i = 0; i < xy.length; i += 2) {
                xy[i] = xOffset + (xy[i] - camera.minX()) * scale;
                xy[i + 1] = height - yOffset - (xy[i + 1] - camera.minY()) * scale;
            }
            short[] triangles = part.triangles();
            for (int i = 0; i < triangles.length; i += 3) {
                raster.triangle(xy, part.uv(), (triangles[i] & 0xffff) * 2,
                        (triangles[i + 1] & 0xffff) * 2, (triangles[i + 2] & 0xffff) * 2,
                        model.texture, model.textureWidth, model.textureHeight,
                        part.red(), part.green(), part.blue(), part.alpha(), part.additive());
            }
        });
        return raster.image;
    }

    private void pose(String action, float seconds, AnimationType variant) {
        Animation animation = model.data.findAnimation(action);
        if (animation == null || !Float.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid pose: " + action);
        skeleton.setToSetupPose();
        animation.apply(skeleton, 0, seconds, false, null, 1, MixBlend.setup, MixDirection.in);
        if (variant == AnimationType.TALK) {
            Bone mouth = skeleton.findBone("F_R_Mouth_II");
            if (mouth != null) mouth.setScaleY(mouth.getScaleY() * (1 + .65f * Math.abs((float) Math.sin(seconds * 15))));
            Bone head = skeleton.findBone("F_Head_I");
            if (head != null) head.setRotation(head.getRotation() + 1.5f * (float) Math.sin(seconds * 7));
        } else if (variant == AnimationType.THINKING) {
            Bone head = skeleton.findBone("F_Head_I");
            if (head != null) head.setRotation(head.getRotation() + 5 + (float) Math.sin(seconds * 2));
        }
        skeleton.updateWorldTransform();
    }

    private Bounds measureBounds() {
        float[] extent = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var entry : model.animations().entrySet()) {
            int samples = Math.max(1, (int) Math.ceil(entry.getValue() * 12));
            for (int i = 0; i <= samples; i++) {
                pose(entry.getKey(), entry.getValue() * i / samples, null);
                SpineGeometry.visit(model, skeleton, part -> {
                    for (int n = 0; n < part.vertices().length; n += 2) {
                        float x = part.vertices()[n], y = part.vertices()[n + 1];
                        if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Nonfinite Spine vertices");
                        extent[0] = Math.min(extent[0], x); extent[1] = Math.min(extent[1], y);
                        extent[2] = Math.max(extent[2], x); extent[3] = Math.max(extent[3], y);
                    }
                });
            }
        }
        // Meshes can include large transparent effect areas. Refine the envelope using visible pixels,
        // still sampling every action once and retaining one fixed camera throughout playback.
        float margin = Math.max(extent[2] - extent[0], extent[3] - extent[1]) * .04f;
        if (!Float.isFinite(margin) || margin <= 0) throw new IllegalArgumentException("Empty Spine bounds");
        Bounds coarse = new Bounds(extent[0] - margin, extent[1] - margin, extent[2] + margin, extent[3] + margin);
        float[] visible = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        float scale = Math.min(492f / coarse.width(), 492f / coarse.height());
        float xOffset = (512 - coarse.width() * scale) / 2;
        float yOffset = (512 - coarse.height() * scale) / 2;
        for (var entry : model.animations().entrySet()) {
            int samples = Math.max(1, (int) Math.ceil(entry.getValue() * 12));
            for (int i = 0; i <= samples; i++) {
                pose(entry.getKey(), entry.getValue() * i / samples, null);
                BufferedImage image = renderCurrentPose(coarse, 512, 512);
                int[] pixels = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
                int minX = 512, minY = 512, maxX = -1, maxY = -1;
                for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
                    if ((pixels[y * 512 + x] >>> 24) < 3) continue;
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
                if (maxX < 0) continue;
                visible[0] = Math.min(visible[0], coarse.minX() + (minX - xOffset) / scale);
                visible[1] = Math.min(visible[1], coarse.minY() + (512 - yOffset - maxY - 1) / scale);
                visible[2] = Math.max(visible[2], coarse.minX() + (maxX + 1 - xOffset) / scale);
                visible[3] = Math.max(visible[3], coarse.minY() + (512 - yOffset - minY) / scale);
            }
        }
        if (!Float.isFinite(visible[0])) throw new IllegalArgumentException("Spine has no visible texture pixels");
        margin = Math.max(visible[2] - visible[0], visible[3] - visible[1]) * .04f;
        return new Bounds(visible[0] - margin, visible[1] - margin, visible[2] + margin, visible[3] + margin);
    }
}
