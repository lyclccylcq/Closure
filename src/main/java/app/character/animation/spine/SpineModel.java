package app.character.animation.spine;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.esotericsoftware.spine.*;
import com.esotericsoftware.spine.attachments.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

/** Immutable exported skeleton/atlas data. No GL texture, native backend or network. */
public final class SpineModel {
    public static final String RESOURCE_ROOT = "/animations/closure/spine/";
    private static final String NAME = "build_char_4228_closur";
    final SkeletonData data;
    final Map<String, Region> regions;
    final int[] texture;
    final int textureWidth;
    final int textureHeight;
    SpineRenderer.Bounds cameraBounds;
    private final Map<String, Float> animations;

    record Region(int x, int y, int width, int height, int originalWidth, int originalHeight,
                  float offsetX, float offsetY, boolean rotated) {
        // The source UVs refer to the original, untrimmed attachment. Atlas Y points down.
        void uv(float u, float v, float[] result, int at) {
            if (rotated) {
                result[at] = x - (originalHeight - offsetY - height) + v * originalHeight;
                result[at + 1] = y - (originalWidth - offsetX - width) + (1 - u) * originalWidth;
            } else {
                result[at] = x - offsetX + u * originalWidth;
                result[at + 1] = y - (originalHeight - offsetY - height) + v * originalHeight;
            }
        }
    }

    private SpineModel(SkeletonData data, Map<String, Region> regions, BufferedImage image) {
        this.data = data;
        this.regions = Map.copyOf(regions);
        textureWidth = image.getWidth();
        textureHeight = image.getHeight();
        // Upstream PNG channels already contain PMA. Preserve those channels; do not premultiply twice.
        texture = image.getRGB(0, 0, textureWidth, textureHeight, null, 0, textureWidth);
        Map<String, Float> exported = new LinkedHashMap<>();
        for (var animation : data.getAnimations()) exported.put(animation.getName(), animation.getDuration());
        animations = Collections.unmodifiableMap(exported);
    }

    public static SpineModel bundled() {
        return Bundled.MODEL;
    }

    private static final class Bundled {
        static final SpineModel MODEL = load(RESOURCE_ROOT);
    }

    public static SpineModel load(String root) {
        return load(root, SpineModel::bytes);
    }

    @FunctionalInterface
    interface ResourceBytes { byte[] read(String path) throws IOException; }

    static SpineModel load(String root, ResourceBytes source) {
        if (!root.startsWith("/") || !root.endsWith("/")) {
            throw new IllegalArgumentException("Spine resource root must be absolute and end in /");
        }
        try {
            var atlas = new TextureAtlas.TextureAtlasData(
                    new MemoryFile(NAME + ".atlas", source.read(root + NAME + ".atlas")), new FileHandle(""), false);
            if (atlas.getPages().size != 1) throw new IOException("Expected a single atlas page");
            var page = atlas.getPages().first();
            if (!page.textureFile.name().equals(NAME + ".png")) throw new IOException("Unexpected atlas page");
            BufferedImage image;
            try (InputStream input = new ByteArrayInputStream(source.read(root + NAME + ".png"))) {
                image = ImageIO.read(input);
            }
            if (image == null || image.getWidth() != page.width || image.getHeight() != page.height) {
                throw new IOException("Spine atlas and PNG dimensions differ");
            }
            Map<String, Region> regions = new HashMap<>();
            for (var r : atlas.getRegions()) {
                if (r.degrees != 0 && r.degrees != 90) throw new IOException("Unsupported atlas rotation: " + r.degrees);
                if (r.originalWidth <= 0 || r.originalHeight <= 0 || r.flip) throw new IOException("Invalid atlas region: " + r.name);
                regions.put(r.name, new Region(r.left, r.top, r.width, r.height,
                        r.originalWidth, r.originalHeight, r.offsetX, r.offsetY, r.rotate));
            }
            var data = new SkeletonBinary(new DataLoader(regions)).readSkeletonData(
                    new MemoryFile(NAME + ".skel", source.read(root + NAME + ".skel")));
            if (!data.getVersion().startsWith("3.8.")) throw new IOException("Spine 3.8 export required");
            for (String action : List.of("Relax", "Interact", "Sit", "Sleep")) {
                var animation = data.findAnimation(action);
                if (animation == null || !Float.isFinite(animation.getDuration()) || animation.getDuration() <= 0) {
                    throw new IOException("Missing or invalid required Spine action: " + action);
                }
            }
            for (var slot : data.getSlots()) {
                if (slot.getBlendMode() != BlendMode.normal && slot.getBlendMode() != BlendMode.additive) {
                    throw new IOException("Unsupported slot blend: " + slot.getBlendMode());
                }
                if (slot.getDarkColor() != null) throw new IOException("Two-colour slot tint is not supported");
            }
            return new SpineModel(data, regions, image);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot load Spine resources at " + root + ": " + failure.getMessage(), failure);
        }
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream input = SpineModel.class.getResourceAsStream(path)) {
            if (input == null) throw new FileNotFoundException(path);
            return input.readAllBytes();
        }
    }

    private static final class MemoryFile extends FileHandle {
        private final byte[] bytes;
        MemoryFile(String name, byte[] bytes) { super(name); this.bytes = bytes; }
        @Override public InputStream read() { return new ByteArrayInputStream(bytes); }
    }

    private record DataLoader(Map<String, Region> regions) implements AttachmentLoader {
        private void require(String path) {
            if (!regions.containsKey(path)) throw new IllegalArgumentException("Missing atlas region: " + path);
        }
        public RegionAttachment newRegionAttachment(Skin skin, String name, String path) {
            require(path); return new RegionAttachment(name);
        }
        public MeshAttachment newMeshAttachment(Skin skin, String name, String path) {
            require(path); return new MeshAttachment(name);
        }
        public BoundingBoxAttachment newBoundingBoxAttachment(Skin skin, String name) { return new BoundingBoxAttachment(name); }
        public ClippingAttachment newClippingAttachment(Skin skin, String name) {
            throw new IllegalArgumentException("Clipping attachments are not supported by this CPU renderer");
        }
        public PathAttachment newPathAttachment(Skin skin, String name) { return new PathAttachment(name); }
        public PointAttachment newPointAttachment(Skin skin, String name) { return new PointAttachment(name); }
    }

    public Map<String, Float> animations() {
        return animations;
    }

    public int boneCount() { return data.getBones().size; }
    public String version() { return data.getVersion(); }
}
