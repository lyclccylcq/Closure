package app.character.animation.spine;

import com.esotericsoftware.spine.*;
import com.esotericsoftware.spine.attachments.*;

final class SpineGeometry {
    private static final short[] QUAD = {0, 1, 2, 2, 3, 0};
    record Part(float[] vertices, float[] uv, short[] triangles, float red, float green,
                float blue, float alpha, boolean additive) { }
    interface Visitor { void accept(Part part); }

    static void visit(SpineModel model, Skeleton skeleton, Visitor visitor) {
        for (Slot slot : skeleton.getDrawOrder()) {
            if (!slot.getBone().isActive()) continue;
            Attachment attachment = slot.getAttachment();
            float[] vertices;
            float[] uv;
            short[] triangles;
            com.badlogic.gdx.graphics.Color colour;
            if (attachment instanceof RegionAttachment region) {
                var atlas = model.regions.get(region.getPath());
                vertices = new float[8];
                uv = new float[8];
                float left = atlas.offsetX() / atlas.originalWidth();
                float right = (atlas.offsetX() + atlas.width()) / atlas.originalWidth();
                float top = (atlas.originalHeight() - atlas.offsetY() - atlas.height()) / atlas.originalHeight();
                float bottom = (atlas.originalHeight() - atlas.offsetY()) / atlas.originalHeight();
                float[] originalUv = {left, bottom, left, top, right, top, right, bottom};
                double angle = Math.toRadians(region.getRotation());
                float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
                Bone bone = slot.getBone();
                for (int i = 0; i < 8; i += 2) {
                    float x = (originalUv[i] - .5f) * region.getWidth() * region.getScaleX();
                    float y = (.5f - originalUv[i + 1]) * region.getHeight() * region.getScaleY();
                    float localX = x * cos - y * sin + region.getX();
                    float localY = x * sin + y * cos + region.getY();
                    vertices[i] = localX * bone.getA() + localY * bone.getB() + bone.getWorldX();
                    vertices[i + 1] = localX * bone.getC() + localY * bone.getD() + bone.getWorldY();
                    atlas.uv(originalUv[i], originalUv[i + 1], uv, i);
                }
                triangles = QUAD;
                colour = region.getColor();
            } else if (attachment instanceof MeshAttachment mesh) {
                vertices = new float[mesh.getWorldVerticesLength()];
                mesh.computeWorldVertices(slot, 0, vertices.length, vertices, 0, 2);
                uv = new float[vertices.length];
                var atlas = model.regions.get(mesh.getPath());
                float[] originalUv = mesh.getRegionUVs();
                for (int i = 0; i < uv.length; i += 2) atlas.uv(originalUv[i], originalUv[i + 1], uv, i);
                triangles = mesh.getTriangles();
                colour = mesh.getColor();
            } else {
                continue; // Non-rendering bounding boxes, paths and points.
            }
            var overall = skeleton.getColor();
            var tint = slot.getColor();
            float alpha = overall.a * tint.a * colour.a;
            if (alpha <= .0001f) continue;
            visitor.accept(new Part(vertices, uv, triangles, overall.r * tint.r * colour.r,
                    overall.g * tint.g * colour.g, overall.b * tint.b * colour.b, alpha,
                    slot.getData().getBlendMode() == BlendMode.additive));
        }
    }
}
