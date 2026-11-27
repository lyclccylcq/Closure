package app.ui.pet;

import app.character.CharacterController;
import app.character.CharacterState;
import app.character.animation.AnimationType;
import app.character.animation.sprite.SpriteAnimationPlayer;
import app.character.animation.sprite.SpriteFixtures;
import app.character.animation.sprite.SpriteModel;
import app.service.AssistantSpeechService;
import app.service.ChatSpeechController;
import app.service.SpeechLifecycleListener;
import app.ui.PetWindow;
import app.ui.chat.WorkbenchWindow;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeSpriteTest", matches="true")
class NewModelWindowTest {
    @TempDir Path temporary;
    @Test void newCharacterUsesSpeechLifecycleAndKeepsDragAndMenuEntry() throws Exception {
        String oldRenderer = System.getProperty("closure.animation");
        String oldAssets = System.getProperty("closure.petAssetsDirectory");
        String real = System.getProperty("closure.realPetAssets");
        Path assets = real == null ? SpriteFixtures.create(temporary.resolve("synthetic")) : Path.of(real);
        System.setProperty("closure.animation", "sprite"); System.setProperty("closure.petAssetsDirectory", assets.toString());
        SpriteModel model = SpriteModel.local();
        assertEquals(real == null, model.synthetic());
        for (String name : SpriteModel.CLIPS) {
            for (int frame = 0; frame < model.frames(name); frame += 4) {
                BufferedImage image = model.frame(name, frame);
                assertTrue(image.getColorModel().hasAlpha());
                boolean visible = false, transparent = false;
                for (int y = 0; y < image.getHeight(); y += 4) {
                    for (int x = 0; x < image.getWidth(); x += 4) {
                        int alpha = image.getRGB(x, y) >>> 24;
                        visible |= alpha > 100; transparent |= alpha == 0;
                    }
                }
                assertTrue(visible && transparent, "Each decoded animation frame must contain character and transparent pixels");
                assertTrue(model.cachedPages() <= SpriteModel.MAX_CACHED_PAGES);
            }
        }
        PetWindow window = null;
        ChatSpeechController speech = null;
        SpriteAnimationPlayer player = null;
        try {
            window = edt(() -> PetWindow.startupSmokeWindow(() -> { throw new AssertionError("Menu inspection must not create workbench"); }));
            PetWindow pet = window;
            PetAnimationPanel panel = (PetAnimationPanel) field(pet, "petAnimationPanel");
            assertEquals("sprite", panel.rendererName()); assertFalse(panel.supportsWalking());
            player = (SpriteAnimationPlayer) panel.animationPlayer();
            SpriteAnimationPlayer renderer = player;
            CharacterController character = (CharacterController) field(pet, "characterController");
            edt(() -> { pet.setVisible(true); character.play(AnimationType.IDLE); return null; });
            await(() -> renderer.currentClip().equals("idle")); capture(panel, "idle");
            assertFalse(Arrays.stream(Window.getWindows()).anyMatch(WorkbenchWindow.class::isInstance));

            Runnable finishThinking = character.thinkingStarted();
            await(() -> renderer.currentClip().equals("think_idle")); capture(panel, "think");
            CompletableFuture<Void> audio = new CompletableFuture<>();
            var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                    new VoiceProfile("synthetic", temporary.resolve("unused.wav"), "Synthetic", "zh")), message -> {}, true);
            speech = new ChatSpeechController(new AssistantSpeechService() {
                public CompletableFuture<Void> speak(String text, VoiceProfile profile) { return audio; }
                public void stop() { }
            }, profiles, true, message -> {});
            speech.setSpeechLifecycleListener(new SpeechLifecycleListener() {
                public void onSpeechStarted() { character.setTalking(true); }
                public void onSpeechFinished() { character.setTalking(false); }
            });
            CompletableFuture<Void> spoken = speech.speakFullTextAsync("这是一段本地状态回归。");
            await(() -> renderer.currentClip().equals("talk"));
            assertEquals(CharacterState.TALKING, character.state()); capture(panel, "talk");
            audio.complete(null); spoken.get(4, TimeUnit.SECONDS);
            await(() -> renderer.currentClip().equals("think_idle"));
            finishThinking.run(); await(() -> renderer.currentClip().equals("idle"));

            edt(() -> {
                Point original = pet.getLocation();
                dispatch(panel, MouseEvent.MOUSE_PRESSED, original.x + 100, original.y + 120, false);
                dispatch(panel, MouseEvent.MOUSE_DRAGGED, original.x + 135, original.y + 135, false);
                assertEquals(new Point(original.x + 35, original.y + 15), pet.getLocation());
                assertEquals(CharacterState.DRAGGING, character.state());
                dispatch(panel, MouseEvent.MOUSE_RELEASED, original.x + 135, original.y + 135, false);
                return null;
            });
            await(() -> character.state() == CharacterState.IDLE);
            edt(() -> {
                dispatch(panel, MouseEvent.MOUSE_PRESSED, pet.getX() + 100, pet.getY() + 100, true);
                JPopupMenu menu = (JPopupMenu) field(pet, "popupMenu"); assertTrue(menu.isShowing());
                assertTrue(Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                        .anyMatch(item -> "工作台".equals(item.getText())));
                JMenu actions = Arrays.stream(menu.getComponents()).filter(JMenu.class::isInstance).map(JMenu.class::cast)
                        .filter(item -> "动作".equals(item.getText())).findFirst().orElseThrow();
                List<String> labels = Arrays.stream(actions.getMenuComponents()).filter(JMenuItem.class::isInstance)
                        .map(JMenuItem.class::cast).map(JMenuItem::getText).toList();
                assertTrue(labels.containsAll(List.of("待机", "安静待机", "打招呼", "互动", "开心", "难过")));
                assertFalse(labels.contains("行走")); assertFalse(labels.contains("自主走动")); menu.setVisible(false);
                // Finish the right-button gesture before the next left-button action.
                dispatch(panel, MouseEvent.MOUSE_RELEASED, pet.getX() + 100, pet.getY() + 100, true);
                menu.setVisible(false);
                return null;
            });
            for (var action : new AnimationType[]{AnimationType.HAPPY, AnimationType.GREETING, AnimationType.SAD}) {
                character.play(action);
                String clip = action == AnimationType.HAPPY ? "happy" : action == AnimationType.GREETING ? "celebrate" : "sad_idle";
                await(() -> renderer.currentClip().equals(clip)); capture(panel, clip);
                await(() -> character.state() == CharacterState.IDLE);
            }
            assertTrue(model.cachedPages() <= SpriteModel.MAX_CACHED_PAGES);
            System.out.println("CLOSURE_NEW_MODEL_NATIVE_PASS synthetic=" + model.synthetic()
                    + " speech=true thinking=true recovery=true drag=true menu=true boundedCache=true");
        } finally {
            if (window != null) { PetWindow pet = window; edt(() -> { pet.dispose(); return null; }); }
            if (speech != null) speech.close();
            if (player != null) assertTrue(player.awaitClosed(2000));
            restore("closure.animation", oldRenderer); restore("closure.petAssetsDirectory", oldAssets);
        }
    }
    private void capture(PetAnimationPanel panel, String name) throws Exception {
        String output = System.getProperty("closure.spriteEvidence"); if (output == null) return;
        Thread.sleep(300); // Sample after the initial fade, rather than its previous pose.
        BufferedImage image = edt(() -> {
            var canvas = new BufferedImage(panel.getWidth() * 2, panel.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
            var graphics = canvas.createGraphics(); graphics.scale(2, 2);
            try { panel.paint(graphics); } finally { graphics.dispose(); }
            return canvas;
        });
        Files.createDirectories(Path.of(output)); ImageIO.write(image, "png", Path.of(output, name + ".png").toFile());
    }
    private static void dispatch(PetAnimationPanel panel, int id, int x, int y, boolean popup) {
        Point location = panel.getLocationOnScreen();
        panel.dispatchEvent(new MouseEvent(panel, id, System.currentTimeMillis(), 0, x - location.x, y - location.y,
                x, y, 1, popup, popup ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1));
    }
    private static Object field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeLater(task); return task.get(8, TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(15);
        assertTrue(check.getAsBoolean(), "Native character state did not settle");
    }
    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
