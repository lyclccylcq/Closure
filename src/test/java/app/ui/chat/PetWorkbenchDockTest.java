package app.ui.chat;

import app.character.CharacterController;
import app.character.CharacterState;
import app.character.animation.AnimationType;
import app.character.animation.sprite.SpriteAnimationPlayer;
import app.character.animation.sprite.SpriteFixtures;
import app.character.animation.sprite.SpriteModel;
import app.service.AssistantSpeechService;
import app.service.ChatService;
import app.service.ChatSpeechController;
import app.service.SpeechLifecycleListener;
import app.ui.PetWindow;
import app.ui.pet.PetAnimationPanel;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import javafx.embed.swing.JFXPanel;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.image.ImageView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.PickResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeDockTest", matches="true")
class PetWorkbenchDockTest {
    @TempDir Path temporary;
    private WorkbenchWindow workbench;
    private final BlockingQueue<CompletableFuture<Void>> audio = new LinkedBlockingQueue<>();

    @Test void oneLiveCharacterDocksAndRestoresWithoutCoveringChatOrLosingSessions() throws Exception {
        String oldRenderer = System.getProperty("closure.animation"), oldAssets = System.getProperty("closure.petAssetsDirectory");
        String real = System.getProperty("closure.realPetAssets");
        Path assets = real == null ? SpriteFixtures.create(temporary.resolve("assets")) : Path.of(real);
        System.setProperty("closure.animation", "sprite"); System.setProperty("closure.petAssetsDirectory", assets.toString());
        SpriteModel.local();
        var clients = new ArrayList<ChatWindowLifecycleTest.Client>();
        var character = new AtomicReference<CharacterController>();
        var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                new VoiceProfile("synthetic", temporary.resolve("unused.wav"), "Synthetic", "zh")), text -> {}, true);
        var speech = new ChatSpeechController(new AssistantSpeechService() {
            public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
                var pending = new CompletableFuture<Void>(); audio.add(pending); return pending;
            }
            public void stop() { }
        }, profiles, true, text -> {});
        speech.setSpeechLifecycleListener(new SpeechLifecycleListener() {
            public void onSpeechStarted() { character.get().setTalking(true); }
            public void onSpeechFinished() { character.get().setTalking(false); }
        });
        AtomicInteger factories = new AtomicInteger();
        PetWindow pet = null;
        SpriteAnimationPlayer renderer = null;
        try {
            pet = swing(() -> PetWindow.startupSmokeWindow(() -> {
                factories.incrementAndGet();
                workbench = new WorkbenchWindow(() -> {
                    var client = new ChatWindowLifecycleTest.Client(); clients.add(client); return new ChatService(client);
                }, speech, character.get());
                return workbench;
            }));
            PetWindow owner = pet;
            PetAnimationPanel panel = (PetAnimationPanel) field(owner, "petAnimationPanel");
            character.set((CharacterController) field(owner, "characterController"));
            renderer = (SpriteAnimationPlayer) panel.animationPlayer();
            SpriteAnimationPlayer player = renderer;
            var speechField = PetWindow.class.getDeclaredField("speechController"); speechField.setAccessible(true);
            speechField.set(owner, speech);
            JLabel label = (JLabel) panel.getComponent(0);
            swing(() -> { owner.setVisible(true); character.get().play(AnimationType.IDLE); return null; });
            CountDownLatch samples = new CountDownLatch(12);
            PropertyChangeListener sample = event -> samples.countDown();
            long paints = (long) field(owner, "bufferedPaintCount");
            swing(() -> { label.addPropertyChangeListener("icon", sample); return null; });
            assertTrue(samples.await(4, TimeUnit.SECONDS));
            swing(() -> { label.removePropertyChangeListener("icon", sample); return null; });
            await(() -> (long) field(owner, "bufferedPaintCount") - paints >= 4);
            int observers = swing(() -> label.getPropertyChangeListeners("icon").length);

            openFromMenu(owner, panel);
            workbench.whenReady().get(10, TimeUnit.SECONDS);
            await(() -> !owner.isVisible() && (boolean) field(owner, "workbenchDocked"));
            await(() -> fx(() -> view().lookup("#workbench-pet-dock") != null));
            assertSame(player, panel.animationPlayer()); assertEquals(1, factories.get());
            assertEquals(observers + 1, swing(() -> label.getPropertyChangeListeners("icon").length));
            CountDownLatch fxBlocked = new CountDownLatch(1), resumeFx = new CountDownLatch(1);
            Platform.runLater(() -> {
                fxBlocked.countDown();
                try { assertTrue(resumeFx.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(fxBlocked.await(2, TimeUnit.SECONDS));
                Thread.sleep(160);
                swing(() -> { assertFalse(owner.isVisible()); return null; });
                assertTrue(((AtomicBoolean) field(workbench, "petFrameQueued")).get());
                assertNotNull(((AtomicReference<?>) field(workbench, "petFrame")).get());
            } finally { resumeFx.countDown(); }
            fx(() -> {
                assertEquals(1, view().lookupAll("#workbench-mascot").size());
                assertEquals("desktop-model", mascot().getProperties().get("source"));
                assertNotNull(mascot().getImage()); assertDockLayout(); capture("dock-welcome");
                editor().setText("工作台内置模型状态回归");
                editor().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                return null;
            });
            var request = fx(clients::getFirst).take();
            await(() -> player.currentClip().equals("think_idle"));
            fx(() -> { assertDockLayout(); capture("dock-thinking"); return null; });
            request.finish("模型回归通过。");
            CompletableFuture<Void> spoken = audio.poll(5, TimeUnit.SECONDS); assertNotNull(spoken);
            await(() -> player.currentClip().equals("talk"));
            spoken.complete(null); await(() -> character.get().state() == CharacterState.IDLE);

            fx(() -> {
                ((Button) view().lookup("#workbench-new-session")).fire();
                assertEquals(2, ((ListView<?>) view().lookup("#workbench-sessions")).getItems().size());
                editor().setText("保留未发送草稿");
                mascot().fireEvent(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        50, 60, 50, 60, MouseButton.PRIMARY, 1, false, false, false, false,
                        false, false, false, false, false, true, new PickResult(mascot(), 50, 60)));
                return null;
            });
            await(() -> fx(() -> view().lookup("#workbench-pet-bubble").isVisible()));
            CompletableFuture<Void> touch = audio.poll(4, TimeUnit.SECONDS); assertNotNull(touch); touch.complete(null);
            fx(() -> {
                var point = mascot().localToScreen(80, 100);
                mascot().fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 80, 100,
                        point.getX(), point.getY(), false, new PickResult(mascot(), 80, 100)));
                return null;
            });
            await(() -> swing(() -> ((JPopupMenu) field(owner, "popupMenu")).isShowing()));
            swing(() -> {
                JPopupMenu menu = (JPopupMenu) field(owner, "popupMenu");
                item(menu, "回到桌面").doClick(); menu.setVisible(false); return null;
            });
            await(() -> owner.isVisible() && !workbench.isVisible());
            assertEquals(observers, swing(() -> label.getPropertyChangeListeners("icon").length));
            openFromMenu(owner, panel);
            await(() -> !owner.isVisible());
            assertEquals(1, factories.get());
            fx(() -> { assertEquals("保留未发送草稿", editor().getText()); assertDockLayout(); return null; });
            swing(() -> { workbench.setSize(1040, 720); return null; });
            await(() -> fx(() -> view().getWidth() < 1041));
            fx(() -> { assertDockLayout(); capture("dock-compact"); return null; });
            boolean minimized = Toolkit.getDefaultToolkit().isFrameStateSupported(JFrame.ICONIFIED);
            if (minimized) {
                swing(() -> { workbench.setExtendedState(JFrame.ICONIFIED); return null; });
                await(owner::isVisible);
                swing(() -> { workbench.setExtendedState(JFrame.NORMAL); return null; });
                await(() -> !owner.isVisible());
            }
            swing(() -> { workbench.dispatchEvent(new WindowEvent(workbench, WindowEvent.WINDOW_CLOSING)); return null; });
            await(owner::isVisible);
            openFromMenu(owner, panel); await(() -> !owner.isVisible());
            swing(() -> { owner.dispose(); return null; });
            fx(() -> null); swing(() -> null);
            assertFalse(owner.isVisible()); assertFalse(owner.isDisplayable());
            assertTrue(player.awaitClosed(2000));
            System.out.println("CLOSURE_PET_DOCK_NATIVE_PASS synthetic=" + player.model().synthetic()
                    + " oneRenderer=true corner=true controlsClear=true speech=true click=true menu=true sessions=true restore=true shutdown=true minimize=" + minimized + " bufferedPaints="
                    + ((long) field(owner, "bufferedPaintCount") - paints));
        } finally {
            if (pet != null) { PetWindow owner = pet; swing(() -> { owner.dispose(); return null; }); }
            speech.close(); if (renderer != null) assertTrue(renderer.awaitClosed(2000));
            restore("closure.animation", oldRenderer); restore("closure.petAssetsDirectory", oldAssets);
        }
    }

    private void assertDockLayout() {
        view().applyCss(); view().layout();
        Node dock = view().lookup("#workbench-pet-dock");
        Bounds corner = dock.localToScene(dock.getBoundsInLocal());
        Bounds input = editor().localToScene(editor().getBoundsInLocal());
        assertFalse(corner.intersects(input), "Model must leave the input usable");
        Node send = view().lookup("#workbench-send");
        assertFalse(corner.intersects(send.localToScene(send.getBoundsInLocal())), "Model must leave Send usable");
        assertTrue(view().getWidth() - corner.getMaxX() >= 0 && view().getWidth() - corner.getMaxX() < 45);
        assertTrue(view().getHeight() - corner.getMaxY() >= 0 && view().getHeight() - corner.getMaxY() < 25);
    }
    private void capture(String name) throws Exception {
        String output = System.getProperty("closure.dockEvidence"); if (output == null) return;
        Path folder = Path.of(output); Files.createDirectories(folder);
        ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null), "png", folder.resolve(name + ".png").toFile());
    }
    private WorkbenchView view() { return (WorkbenchView) ((JFXPanel) workbench.getContentPane()).getScene().getRoot(); }
    private ImageView mascot() { return (ImageView) view().lookup("#workbench-mascot"); }
    private TextArea editor() { return (TextArea) view().lookup("#workbench-editor"); }
    private static void openFromMenu(PetWindow owner, PetAnimationPanel panel) throws Exception {
        swing(() -> {
            panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                    90, 100, owner.getX() + 90, owner.getY() + 100, 1, true, MouseEvent.BUTTON3));
            JPopupMenu menu = (JPopupMenu) field(owner, "popupMenu"); assertTrue(menu.isShowing());
            item(menu, "工作台").doClick(); menu.setVisible(false); return null;
        });
    }
    private static JMenuItem item(JPopupMenu menu, String text) {
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                .filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }
    private static Object field(Object value, String name) {
        try { var f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void await(CheckedCondition condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.test() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.test(), "Dock lifecycle did not settle");
    }
    private interface CheckedCondition { boolean test() throws Exception; }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
