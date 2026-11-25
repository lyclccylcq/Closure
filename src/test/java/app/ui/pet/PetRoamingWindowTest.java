package app.ui.pet;

import app.character.CharacterController;
import app.character.CharacterState;
import app.character.DesktopRoamingController;
import app.character.animation.AnimationController;
import app.character.animation.AnimationType;
import app.character.animation.spine.SpineAnimationPlayer;
import app.character.animation.spine.SpineModel;
import app.character.animation.spine.SpineRenderer;
import app.ui.PetWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real pet window only: no chat services, private files, voice or other applications. */
@EnabledIfSystemProperty(named = "closure.nativeRoamingTest", matches = "true")
class PetRoamingWindowTest {
    private PetWindow window;
    private PetAnimationPanel panel;
    private Timer timer;

    @AfterEach
    void closeWindowAndRenderer() throws Exception {
        if (window != null) edt(() -> { window.dispose(); return null; });
        if (timer != null) assertFalse(timer.isRunning());
        if (panel != null && panel.animationPlayer() instanceof SpineAnimationPlayer spine) {
            assertTrue(spine.awaitClosed(1500));
        }
    }

    @Test
    void actualWindowWalksTurnsAndYieldsToMenuConversationDragAndSleep() throws Exception {
        new SpineRenderer(SpineModel.bundled()); // CPU resource preparation before using the EDT.
        AtomicLong now = new AtomicLong();
        DesktopRoamingController roaming = new DesktopRoamingController(now::get, () -> 0);
        CountDownLatch frames = new CountDownLatch(2);
        Rectangle area = edt(() -> {
            window = PetWindow.startupSmokeWindow();
            panel = (PetAnimationPanel) field(window, "petAnimationPanel");
            assertEquals("spine", panel.rendererName());
            ((CharacterController) field(window, "characterController")).close();
            var character = new CharacterController(new AnimationController(panel.animationPlayer()),
                    now::get, Duration.ofMinutes(2));
            setField(window, "characterController", character);
            setField(window, "roamingController", roaming);
            invoke(window, "initPopupMenu");
            Rectangle usable = (Rectangle) invoke(window, "usableScreenBounds");
            assertTrue(usable.width > 500 && usable.height > 480, "Native display must fit the test pet");
            int y = usable.y + 40;
            Point cursor = MouseInfo.getPointerInfo().getLocation();
            if (new Rectangle(usable.x, y, 230, 220).contains(cursor)) {
                y = usable.y + usable.height - 240;
            }
            window.setLocation(usable.x + 3, y);
            window.setVisible(true);
            character.play(AnimationType.IDLE);
            ((JLabel) panel.getComponent(0)).addPropertyChangeListener("icon", event -> frames.countDown());
            now.addAndGet(Duration.ofSeconds(20).toNanos());
            invoke(window, "tickRoaming");
            assertEquals(CharacterState.WALKING, character.state());
            assertTrue(panel.isFacingLeft());
            return usable;
        });
        assertTrue(frames.await(4, TimeUnit.SECONDS));
        capture("walking-left.png");

        CountDownLatch moved = new CountDownLatch(1);
        int y = edt(() -> {
            int initialX = window.getX();
            window.addComponentListener(new ComponentAdapter() {
                @Override public void componentMoved(ComponentEvent event) {
                    if (window.getX() != initialX) moved.countDown();
                }
            });
            now.addAndGet(100_000_000);
            invoke(window, "startRoamingTimer");
            timer = (Timer) field(window, "roamingTimer");
            return window.getY();
        });
        assertTrue(moved.await(4, TimeUnit.SECONDS), "Real Swing timer must move the actual JFrame");
        edt(() -> {
            timer.stop();
            assertEquals(area.x, window.getX());
            assertEquals(y, window.getY());
            assertFalse(panel.isFacingLeft());
            now.addAndGet(100_000_000);
            invoke(window, "tickRoaming");
            assertEquals(area.x + 4, window.getX());
            assertTrue(area.contains(window.getBounds()));
            return null;
        });
        capture("walking-right.png");

        edt(() -> {
            CharacterController character = (CharacterController) field(window, "characterController");
            Point stopped = window.getLocation();
            JPopupMenu popup = (JPopupMenu) field(window, "popupMenu");
            popup.show(window, 10, 10);
            now.addAndGet(100_000_000);
            invoke(window, "tickRoaming");
            assertEquals(stopped, window.getLocation());
            assertEquals(CharacterState.IDLE, character.state());
            popup.setVisible(false);

            Runnable hideConversation = character.conversationOpened();
            invoke(window, "tickRoaming");
            assertEquals(CharacterState.THINKING, character.state());
            assertEquals(stopped, window.getLocation());
            hideConversation.run();
            character.setTalking(true);
            invoke(window, "tickRoaming");
            assertEquals(CharacterState.TALKING, character.state());
            assertEquals(stopped, window.getLocation());
            character.setTalking(false);
            character.play(AnimationType.SLEEP);
            invoke(window, "tickRoaming");
            assertEquals(CharacterState.SLEEPING, character.state());
            assertEquals(stopped, window.getLocation());

            MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 10, 10);
            for (var listener : panel.getMouseListeners()) listener.mousePressed(press);
            assertEquals(CharacterState.PRESSED, character.state());
            MouseEvent drag = mouse(MouseEvent.MOUSE_DRAGGED, 25, 15);
            for (var listener : panel.getMouseMotionListeners()) listener.mouseDragged(drag);
            assertEquals(CharacterState.DRAGGING, character.state());
            Point dragged = window.getLocation();
            assertEquals(stopped.x + 15, dragged.x);
            assertEquals(stopped.y + 5, dragged.y);
            now.addAndGet(100_000_000);
            invoke(window, "tickRoaming");
            assertEquals(dragged, window.getLocation());
            for (var listener : panel.getMouseListeners()) listener.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, 25, 15));

            JCheckBoxMenuItem automatic = (JCheckBoxMenuItem) field(window, "autonomousWalkAction");
            automatic.doClick();
            assertFalse(roaming.isEnabled());
            now.addAndGet(Duration.ofSeconds(35).toNanos());
            invoke(window, "tickRoaming");
            assertEquals(dragged, window.getLocation());
            ((JMenuItem) field(window, "walkAction")).doClick();
            invoke(window, "tickRoaming");
            assertEquals(CharacterState.WALKING, character.state());
            now.addAndGet(Duration.ofMinutes(2).toNanos());
            invoke(window, "tickRoaming");
            assertEquals(CharacterState.SLEEPING, character.state());
            timer.start(); // Disposal must stop an active timer as well as the renderer.
            return null;
        });
        System.out.println("CLOSURE_ROAMING_NATIVE_PASS automatic=true movement=true turn=true facing=true menu=true chat=true speech=true drag=true sleep=true toggle=true");
    }

    private MouseEvent mouse(int id, int x, int y) {
        return new MouseEvent(panel, id, System.currentTimeMillis(), 0, x, y,
                window.getX() + x, window.getY() + y, 1, false, MouseEvent.BUTTON1);
    }

    private void capture(String name) throws Exception {
        String output = System.getProperty("closure.roamingEvidence");
        if (output == null) return;
        BufferedImage image = edt(() -> {
            var rendered = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = rendered.createGraphics();
            try { panel.paint(graphics); } finally { graphics.dispose(); }
            return rendered;
        });
        Path directory = Path.of(output);
        Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve(name).toFile());
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object invoke(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(object);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(result);
        return result.get(10, TimeUnit.SECONDS);
    }
}
