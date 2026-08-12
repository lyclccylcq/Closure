package app.ui.quick;

import app.ui.UiFonts;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Rectangle;
import java.util.List;

/**
 * PetSpeechBubbleWindow 是贴在模型旁边的点击互动气泡。
 * 它只显示可露希尔自己的短台词，不负责用户输入和 AI 聊天。
 */
public class PetSpeechBubbleWindow {
    private static final Object FX_START_LOCK = new Object();
    private static final int WINDOW_WIDTH = 360;
    private static final int WINDOW_HEIGHT = 132;
    private static final int DIALOG_BOTTOM_OVERLAP = 76;
    private static final int DIALOG_RIGHT_SHIFT = 80;
    private static final int DIALOG_EDGE_GAP = 10;
    private static final Duration STREAM_INTERVAL = Duration.millis(55);
    private static final Duration AUTO_HIDE_DELAY = Duration.millis(3200);

    private static boolean fxStarted;

    private Stage stage;
    private Label speechLabel;
    private Timeline streamTimeline;
    private PauseTransition autoHideDelay;
    private int presentationGeneration;

    public PetSpeechBubbleWindow() {
        runOnFxThread(this::initStage);
    }

    public void showNextTo(Rectangle petBounds, String speechText) {
        showNextTo(petBounds, speechText, AUTO_HIDE_DELAY);
    }

    public void showNextTo(Rectangle petBounds, String speechText, Duration autoHideDelay) {
        Rectangle bounds = new Rectangle(petBounds);
        String normalizedSpeech = normalizeSpeech(speechText);
        Duration normalizedAutoHideDelay = normalizeAutoHideDelay(autoHideDelay);

        runOnFxThread(() -> {
            ensureStage();
            stopCurrentPresentation();
            moveStageNextTo(bounds);
            stage.show();
            stage.toFront();
            streamSpeech(normalizedSpeech, normalizedAutoHideDelay);
        });
    }

    public void moveNextTo(Rectangle petBounds) {
        Rectangle bounds = new Rectangle(petBounds);

        runOnFxThread(() -> {
            if (stage != null && stage.isShowing()) {
                moveStageNextTo(bounds);
            }
        });
    }

    public void hide() {
        presentationGeneration++;

        runOnFxThread(() -> {
            stopCurrentPresentation();
            if (stage != null) {
                stage.hide();
            }
        });
    }

    public void dispose() {
        presentationGeneration++;

        runOnFxThread(() -> {
            stopCurrentPresentation();
            if (stage != null) {
                stage.close();
            }
        });
    }

    private static void runOnFxThread(Runnable task) {
        ensureFxStarted();
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private static void ensureFxStarted() {
        synchronized (FX_START_LOCK) {
            if (fxStarted) {
                return;
            }

            try {
                Platform.startup(() -> Platform.setImplicitExit(false));
            } catch (IllegalStateException exception) {
                Platform.setImplicitExit(false);
            }

            fxStarted = true;
        }
    }

    private void ensureStage() {
        if (stage == null) {
            initStage();
        }
    }

    private void initStage() {
        if (stage != null) {
            return;
        }

        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);

        Scene scene = new Scene(createContent(), WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    private StackPane createContent() {
        speechLabel = new Label();
        speechLabel.setWrapText(true);
        speechLabel.setTextOverrun(OverrunStyle.CLIP);
        speechLabel.setEllipsisString("");
        speechLabel.setMaxWidth(Double.MAX_VALUE);
        speechLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 14px;
                """));
        UiFonts.applyJavaFxFont(speechLabel, 14);

        StackPane root = new StackPane(speechLabel);
        root.setPadding(new Insets(13, 16, 13, 16));
        root.setStyle(UiFonts.fxStyle("""
                -fx-background-color: rgba(8, 12, 18, 0.88);
                -fx-border-color: rgba(58, 174, 210, 0.90);
                -fx-border-width: 1;
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                """));
        return root;
    }

    private void moveStageNextTo(Rectangle petBounds) {
        Point2D location = calculateLocation(petBounds);
        stage.setX(location.getX());
        stage.setY(location.getY());
        stage.toFront();
    }

    private Point2D calculateLocation(Rectangle petBounds) {
        double rightX = petBounds.x + Math.max(0, petBounds.width - WINDOW_WIDTH) + DIALOG_RIGHT_SHIFT;
        double leftX = petBounds.x - Math.max(DIALOG_EDGE_GAP, WINDOW_WIDTH - DIALOG_RIGHT_SHIFT);
        double y = petBounds.y + petBounds.height - DIALOG_BOTTOM_OVERLAP;

        Screen screen = screenFor(petBounds);
        if (screen == null) {
            return new Point2D(rightX, y);
        }

        javafx.geometry.Rectangle2D visualBounds = screen.getVisualBounds();
        double minX = visualBounds.getMinX();
        double maxX = visualBounds.getMaxX() - WINDOW_WIDTH;
        double minY = visualBounds.getMinY();
        double maxY = visualBounds.getMaxY() - WINDOW_HEIGHT;
        double x = chooseNonOverlappingX(petBounds, rightX, leftX, minX, maxX);

        y = Math.max(minY, Math.min(y, maxY));
        return new Point2D(x, y);
    }

    private double chooseNonOverlappingX(
            Rectangle petBounds,
            double rightX,
            double leftX,
            double minX,
            double maxX
    ) {
        if (rightX <= maxX) {
            return rightX;
        }
        if (leftX >= minX) {
            return leftX;
        }

        double rightSpace = maxX - rightX;
        double leftSpace = leftX - minX;
        if (rightSpace >= leftSpace) {
            return Math.max(minX, Math.min(rightX, maxX));
        }

        return Math.max(minX, Math.min(leftX, maxX));
    }

    private Screen screenFor(Rectangle petBounds) {
        List<Screen> screens = Screen.getScreensForRectangle(
                petBounds.x,
                petBounds.y,
                petBounds.width,
                petBounds.height
        );
        if (screens.isEmpty()) {
            return Screen.getPrimary();
        }
        return screens.getFirst();
    }

    private void stopCurrentPresentation() {
        if (streamTimeline != null) {
            streamTimeline.stop();
        }
        if (autoHideDelay != null) {
            autoHideDelay.stop();
        }
    }

    private void streamSpeech(String speechText, Duration autoHideDelay) {
        int generation = ++presentationGeneration;
        int[] codePoints = speechText.codePoints().toArray();
        if (codePoints.length == 0) {
            speechLabel.setText("");
            scheduleAutoHide(generation, autoHideDelay);
            return;
        }

        StringBuilder visibleText = new StringBuilder();
        int[] nextIndex = {0};
        speechLabel.setText("");

        streamTimeline = new Timeline(new KeyFrame(STREAM_INTERVAL, event -> {
            visibleText.appendCodePoint(codePoints[nextIndex[0]]);
            nextIndex[0]++;
            speechLabel.setText(visibleText.toString());
        }));
        streamTimeline.setCycleCount(codePoints.length);
        streamTimeline.setOnFinished(event -> scheduleAutoHide(generation, autoHideDelay));
        streamTimeline.play();
    }

    private void scheduleAutoHide(int generation, Duration delay) {
        autoHideDelay = new PauseTransition(delay);
        autoHideDelay.setOnFinished(event -> {
            if (generation == presentationGeneration && stage != null) {
                stage.hide();
            }
        });
        autoHideDelay.play();
    }

    private String normalizeSpeech(String speechText) {
        if (speechText == null || speechText.isBlank()) {
            return "工程部可露希尔在线。";
        }

        return speechText.trim();
    }

    private Duration normalizeAutoHideDelay(Duration delay) {
        if (delay == null || delay.lessThanOrEqualTo(Duration.ZERO)) {
            return AUTO_HIDE_DELAY;
        }

        return delay;
    }
}
