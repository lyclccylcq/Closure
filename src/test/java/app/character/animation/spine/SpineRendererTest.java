package app.character.animation.spine;

import app.character.animation.AnimationType;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpineRendererTest {
    @Test
    void loadsTheExactPinnedExportAndAllSixActions() throws Exception {
        SpineModel model = SpineModel.bundled();
        assertEquals("3.8.99", model.version());
        assertEquals(297, model.boneCount());
        assertEquals(Set.of("Default", "Interact", "Move", "Relax", "Sit", "Sleep"), model.animations().keySet());
        Map<String, String> hashes = Map.of(
                "atlas", "70428a5b90edcfcd4068a38a576f9ff97cc8a2b9934d68fa8a59d0fa11574077",
                "png", "64ccddedb06756a7a124a351379b6947dcb1e1edf4d4a5d5af727a93f7f59948",
                "skel", "7e6ebfc77754da86683b576a94638f0a5a5a4563ade760ea3592b4302fa89ee9");
        for (var entry : hashes.entrySet()) {
            try (var input = getClass().getResourceAsStream(SpineModel.RESOURCE_ROOT + "build_char_4228_closur." + entry.getKey())) {
                assertNotNull(input);
                assertEquals(entry.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            }
        }
    }

    @Test
    void everySourcePoseHasVisiblePixelsAndTransparentUnclippedBorders() {
        SpineRenderer renderer = new SpineRenderer(SpineModel.bundled());
        var camera = renderer.bounds();
        for (var action : renderer.animations().entrySet()) {
            for (int frame = 0; frame < 4; frame++) {
                BufferedImage image = renderer.render(action.getKey(), action.getValue() * frame / 4, null, 220, 220);
                long visible = Arrays.stream(image.getRGB(0, 0, 220, 220, null, 0, 220)).filter(p -> (p >>> 24) > 10).count();
                assertTrue(visible > 500, action.getKey() + ": empty or tiny model");
                for (int i = 0; i < 220; i++) {
                    assertEquals(0, image.getRGB(i, 0) >>> 24, action.getKey());
                    assertEquals(0, image.getRGB(i, 219) >>> 24, action.getKey());
                    assertEquals(0, image.getRGB(0, i) >>> 24, action.getKey());
                    assertEquals(0, image.getRGB(219, i) >>> 24, action.getKey());
                }
                assertEquals(camera, renderer.bounds(), "Camera must not change with actions");
            }
        }
    }

    @Test
    void sourceMovementAndProceduralVariantsActuallyChangeTheImage() {
        SpineRenderer renderer = new SpineRenderer(SpineModel.bundled());
        assertFalse(Arrays.equals(pixels(renderer.render(AnimationType.TAP, .1f, 220, 220)),
                pixels(renderer.render(AnimationType.TAP, 1.3f, 220, 220))));
        assertFalse(Arrays.equals(pixels(renderer.render(AnimationType.IDLE, .123f, 220, 220)),
                pixels(renderer.render(AnimationType.TALK, .123f, 220, 220))));
        assertFalse(Arrays.equals(pixels(renderer.render("Sit", .123f, null, 220, 220)),
                pixels(renderer.render(AnimationType.THINKING, .123f, 220, 220))));
        assertFalse(Arrays.equals(pixels(renderer.render(AnimationType.THINKING, .123f, 220, 220)),
                pixels(renderer.render(AnimationType.THINKING_TALK, .123f, 220, 220))));
    }

    @Test
    void walkingUsesTheExistingMoveAndConversationSpeechKeepsTheSitBody(){
        SpineRenderer renderer=new SpineRenderer(SpineModel.bundled());
        assertEquals("Move",SpineRenderer.sourceAction(AnimationType.WALK));
        assertEquals(8f,renderer.duration(AnimationType.WALK));
        assertEquals("Sit",SpineRenderer.sourceAction(AnimationType.THINKING_TALK));
        assertEquals(SpineRenderer.sourceAction(AnimationType.THINKING),SpineRenderer.sourceAction(AnimationType.THINKING_TALK));
        assertFalse(Arrays.equals(pixels(renderer.render(AnimationType.WALK,.25f,220,220)),
                pixels(renderer.render(AnimationType.WALK,2.25f,220,220))));
    }

    @Test
    void proceduralPickupAndLandingAreVisibleAndUnclippedAtTheSameCamera(){
        var renderer=new SpineRenderer(SpineModel.bundled());
        var bounds=renderer.bounds();
        for(var type:List.of(AnimationType.PRESSED,AnimationType.DRAGGING,AnimationType.LANDING)){
            for(float time:new float[]{0,.1f,.2f,.38f}){
                var image=renderer.render(type,time,220,220);
                assertTrue(Arrays.stream(pixels(image)).filter(p->(p>>>24)>10).count()>500);
                for(int n=0;n<220;n++){
                    assertEquals(0,image.getRGB(n,0)>>>24,type.toString());
                    assertEquals(0,image.getRGB(n,219)>>>24,type.toString());
                    assertEquals(0,image.getRGB(0,n)>>>24,type.toString());
                    assertEquals(0,image.getRGB(219,n)>>>24,type.toString());
                }
                assertEquals(bounds,renderer.bounds());
            }
        }
        assertFalse(Arrays.equals(pixels(renderer.render(AnimationType.IDLE,.2f,220,220)),
                pixels(renderer.render(AnimationType.DRAGGING,.2f,220,220))));
        assertEquals(.38f,renderer.duration(AnimationType.LANDING));
    }

    @Test
    void liveNativeAndProceduralMixesKeepOneVisibleUnclippedModel(){
        var renderer=new SpineRenderer(SpineModel.bundled());
        renderer.beginLive(AnimationType.IDLE,true);
        var camera=renderer.bounds();
        for(var type:List.of(AnimationType.PRESSED,AnimationType.DRAGGING,AnimationType.LANDING,
                AnimationType.THINKING,AnimationType.THINKING_TALK,AnimationType.IDLE,AnimationType.SLEEP,
                AnimationType.WALK,AnimationType.TAP)){
            renderer.beginLive(type,type!=AnimationType.LANDING && type!=AnimationType.TAP);
            for(int frame=0;frame<15;frame++){
                var image=renderer.renderLive(.033f,frame<7?1:-1,220,220);
                assertTrue(Arrays.stream(pixels(image)).filter(p->(p>>>24)>10).count()>400,type.toString());
                for(int n=0;n<220;n++){
                    assertEquals(0,image.getRGB(n,0)>>>24,type.toString());
                    assertEquals(0,image.getRGB(n,219)>>>24,type.toString());
                    assertEquals(0,image.getRGB(0,n)>>>24,type.toString());
                    assertEquals(0,image.getRGB(219,n)>>>24,type.toString());
                }
                assertEquals(camera,renderer.bounds());
            }
        }
    }

    @Test
    void missingAndCorruptAssetsFailWithAnActionableDiagnostic() {
        assertThrows(IllegalStateException.class, () -> SpineModel.load("/missing-spine/"));
        for (String broken : List.of(".png", ".skel", ".atlas")) {
            var error = assertThrows(IllegalStateException.class, () -> SpineModel.load(SpineModel.RESOURCE_ROOT, path -> {
                if (path.endsWith(broken)) return new byte[]{0, 1, 2};
                try (var input = getClass().getResourceAsStream(path)) {
                    if (input == null) throw new IOException(path);
                    return input.readAllBytes();
                }
            }));
            assertTrue(error.getMessage().contains("Cannot load Spine resources"));
        }
    }

    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, 220, 220, null, 0, 220); }

    @Test
    void simultaneousRenderersSafelyShareImmutableModelData() throws Exception {
        var model = SpineModel.bundled();
        int[] expected = pixels(new SpineRenderer(model).render(AnimationType.IDLE, .5f, 220, 220));
        for (int round = 0; round < 8; round++) {
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<int[]>>();
                for (int i = 0; i < 8; i++) futures.add(workers.submit(() -> {
                    var renderer = new SpineRenderer(model);
                    for (int frame = 0; frame < 12; frame++) {
                        renderer.duration(AnimationType.TALK);
                        renderer.render(AnimationType.TALK, frame / 30f, 220, 220);
                    }
                    return pixels(renderer.render(AnimationType.IDLE, .5f, 220, 220));
                }));
                for (var future : futures) assertArrayEquals(expected, future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void aReadableSkeletonWithAnIncompatibleActionSetFailsBeforePlayback() {
        var failure = assertThrows(IllegalStateException.class, () -> SpineModel.load(SpineModel.RESOURCE_ROOT, path -> {
            try (var input = getClass().getResourceAsStream(path)) {
                assertNotNull(input);
                byte[] data = input.readAllBytes();
                if (path.endsWith(".skel")) {
                    byte[] oldName = "Relax".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    byte[] replacement = "Other".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    for (int i = 0; i <= data.length - oldName.length; i++) {
                        if (Arrays.equals(data, i, i + oldName.length, oldName, 0, oldName.length)) {
                            System.arraycopy(replacement, 0, data, i, replacement.length);
                            break;
                        }
                    }
                }
                return data;
            }
        }));
        assertTrue(failure.getMessage().contains("required Spine action: Relax"));
    }
}
