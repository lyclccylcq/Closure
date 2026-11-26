package app.ui.chat;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

final class WorkbenchMascot extends ImageView {
    private static Sprite cached;
    private final Timeline animation;
    private Window observedWindow;
    private int frame;
    private final ChangeListener<Boolean> showingChanged = (property, oldValue, showing) -> updatePlayback();
    private final ChangeListener<Window> windowChanged = (property, oldValue, window) -> observeWindow(window);

    WorkbenchMascot() {
        setId("workbench-mascot");
        setPreserveRatio(true);
        setMouseTransparent(true);
        Sprite sprite = localSprite();
        if (sprite == null) {
            setImage(new Image(Objects.requireNonNull(getClass().getResource(
                    "/animations/closure/open/frame_096.png")).toExternalForm()));
            setViewport(new Rectangle2D(56, 84, 86, 98));
            setFitHeight(104);
            setSmooth(false);
            animation = null;
        } else {
            setImage(sprite.image());
            setFitHeight(sprite.height());
            setViewport(sprite.viewport(0));
            getProperties().put("source", "dsh-closure-pet");
            animation = new Timeline(new KeyFrame(Duration.seconds(1.0 / sprite.fps()), event -> {
                frame = (frame + 1) % sprite.frames();
                setViewport(sprite.viewport(frame));
            }));
            animation.setCycleCount(Animation.INDEFINITE);
        }
        sceneProperty().addListener((property, oldScene, scene) -> observeScene(oldScene, scene));
    }

    private void observeScene(Scene previous, Scene scene) {
        if (previous != null) {
            previous.windowProperty().removeListener(windowChanged);
            previous.getRoot().visibleProperty().removeListener(showingChanged);
        }
        if (scene != null) {
            scene.windowProperty().addListener(windowChanged);
            scene.getRoot().visibleProperty().addListener(showingChanged);
        }
        observeWindow(scene == null ? null : scene.getWindow());
    }

    private void observeWindow(Window window) {
        if (observedWindow != null) observedWindow.showingProperty().removeListener(showingChanged);
        observedWindow = window;
        if (window != null) window.showingProperty().addListener(showingChanged);
        updatePlayback();
    }

    private void updatePlayback() {
        if (animation == null) return;
        if (observedWindow != null && observedWindow.isShowing() && getScene().getRoot().isVisible()) animation.play();
        else animation.pause();
    }

    boolean isPlaying() {
        return animation != null && animation.getStatus() == Animation.Status.RUNNING;
    }

    private static Sprite localSprite() {
        String configured = System.getProperty("closure.workbenchMascotDirectory",
                System.getenv("CLOSURE_WORKBENCH_MASCOT_DIRECTORY"));
        try {
            Path directory = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".closure", "assets", "workbench")
                    : Path.of(configured);
            Path metadata = directory.resolve("idle.properties");
            if (!Files.isRegularFile(metadata)) return null;
            Properties properties = new Properties();
            try (var stream = Files.newInputStream(metadata)) { properties.load(stream); }
            String name = properties.getProperty("image", "");
            if (!name.matches("idle-[a-zA-Z0-9-]+\\.png")) return null;
            Path path = directory.resolve(name).toAbsolutePath();
            if (cached != null && cached.path().equals(path)) return cached;
            int width = number(properties, "width", 1, 512);
            int height = number(properties, "height", 1, 512);
            int columns = number(properties, "columns", 1, 16);
            int frames = number(properties, "frames", 1, 240);
            int fps = number(properties, "fps", 1, 30);
            long rows = (frames + columns - 1L) / columns;
            Image image = new Image(path.toUri().toString(), false);
            if (image.isError() || image.getWidth() != width * columns || image.getHeight() != height * rows) return null;
            cached = new Sprite(path, image, width, height, columns, frames, fps);
            return cached;
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
    }

    private static int number(Properties properties, String key, int minimum, int maximum) {
        int value = Integer.parseInt(properties.getProperty(key, ""));
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Invalid mascot " + key);
        return value;
    }

    private record Sprite(Path path, Image image, int width, int height, int columns, int frames, int fps) {
        Rectangle2D viewport(int frame) {
            return new Rectangle2D((frame % columns) * width, (frame / columns) * height, width, height);
        }
    }
}
