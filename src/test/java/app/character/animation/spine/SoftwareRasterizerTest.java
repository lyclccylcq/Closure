package app.character.animation.spine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SoftwareRasterizerTest {
    private static final float[] XY = {0, 0, 4, 0, 4, 4, 0, 4};
    private static final float[] UV = {.5f, .5f, .5f, .5f, .5f, .5f, .5f, .5f};

    private void quad(SoftwareRasterizer raster, int texel, boolean additive) {
        raster.triangle(XY, UV, 0, 2, 4, new int[]{texel}, 1, 1, 1, 1, 1, 1, additive);
        raster.triangle(XY, UV, 4, 6, 0, new int[]{texel}, 1, 1, 1, 1, 1, 1, additive);
    }

    @Test
    void sharedTriangleEdgesAreCoveredOnceWithoutHolesOrDoubleAlpha() {
        var raster = new SoftwareRasterizer(4, 4);
        quad(raster, 0x80800000, false);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) assertEquals(0x80ff0000, raster.image.getRGB(x, y));
        quad(raster, 0x80800000, false);
        assertEquals(192, raster.image.getRGB(1, 1) >>> 24, "Two actual layers use source-over");
    }

    @Test
    void additiveGlowKeepsBothColours() {
        var raster = new SoftwareRasterizer(4, 4);
        quad(raster, 0x80800000, false);
        quad(raster, 0x80008000, true);
        assertEquals(0xff808000, raster.image.getRGB(1, 1));
    }

    @Test
    void filteredTransparentEdgesKeepTheirOriginalColour() {
        var raster = new SoftwareRasterizer(4, 4);
        float[] middle = {1, .5f, 1, .5f, 1, .5f, 1, .5f};
        raster.triangle(XY, middle, 0, 2, 4, new int[]{0xffff0000, 0}, 2, 1, 1, 1, 1, 1, false);
        int pixel = raster.image.getRGB(3, 1);
        assertEquals(128, pixel >>> 24);
        assertEquals(255, (pixel >>> 16) & 255, "PMA filtering must not introduce a black fringe");
    }

    @Test
    void rotatedAndTrimmedRegionsMapKnownOriginalCornersToAtlasCorners() {
        float[] uv = new float[2];
        var ordinary = new SpineModel.Region(10, 20, 4, 5, 6, 8, 1, 2, false);
        ordinary.uv(1f / 6, 1f / 8, uv, 0);
        assertArrayEquals(new float[]{10, 20}, uv, .00001f);
        ordinary.uv(5f / 6, 6f / 8, uv, 0);
        assertArrayEquals(new float[]{14, 25}, uv, .00001f);
        var rotated = new SpineModel.Region(10, 20, 2, 3, 4, 6, 1, 2, true);
        rotated.uv(.25f, 1f / 6, uv, 0);
        assertArrayEquals(new float[]{10, 22}, uv, .00001f);
        rotated.uv(.75f, 4f / 6, uv, 0);
        assertArrayEquals(new float[]{13, 20}, uv, .00001f);
    }
}
