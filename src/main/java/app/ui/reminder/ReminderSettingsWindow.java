package app.ui.reminder;

import app.reminder.WorkBreakReminder;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * ReminderSettingsWindow 是工作提醒的小设置窗口。
 * 它负责开关提醒、重新计时和选择提醒间隔，真正的计时逻辑仍由 WorkBreakReminder 处理。
 */
public class ReminderSettingsWindow {
    private static final Object FX_START_LOCK = new Object();
    private static final int WINDOW_WIDTH = 320;
    private static final int WINDOW_HEIGHT = 226;
    private static final int MIN_MINUTES = (int) WorkBreakReminder.MIN_WORK_INTERVAL.toMinutes();
    private static final int MAX_MINUTES = (int) WorkBreakReminder.MAX_WORK_INTERVAL.toMinutes();
    private static final int MINUTE_STEP = 5;

    private static boolean fxStarted;

    private final Consumer<Duration> applyHandler;
    private final Consumer<Boolean> enabledChangeHandler;
    private final Runnable restartHandler;
    private Stage stage;
    private CheckBox reminderEnabledCheckBox;
    private Slider intervalSlider;
    private Label selectedIntervalLabel;

    public ReminderSettingsWindow(
            Consumer<Duration> applyHandler,
            Consumer<Boolean> enabledChangeHandler,
            Runnable restartHandler
    ) {
        this.applyHandler = applyHandler;
        this.enabledChangeHandler = enabledChangeHandler;
        this.restartHandler = restartHandler;
        runOnFxThread(this::initStage);
    }

    public void show(Duration currentInterval, boolean reminderEnabled, Rectangle anchorBounds) {
        int currentMinutes = normalizeMinutes((int) currentInterval.toMinutes());
        Rectangle bounds = new Rectangle(anchorBounds);

        runOnFxThread(() -> {
            ensureStage();
            setSelectedMinutes(currentMinutes);
            reminderEnabledCheckBox.setSelected(reminderEnabled);
            moveNearAnchor(bounds);
            stage.show();
            stage.toFront();
        });
    }

    public void dispose() {
        runOnFxThread(() -> {
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
        stage.setTitle("工作提醒设置");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);

        Scene scene = new Scene(createContent(), WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    private VBox createContent() {
        Label titleLabel = new Label("工作提醒");
        titleLabel.setStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 16px;
                -fx-font-weight: bold;
                """);

        selectedIntervalLabel = new Label();
        selectedIntervalLabel.setStyle("""
                -fx-text-fill: #93b7c9;
                -fx-font-size: 13px;
                """);

        reminderEnabledCheckBox = new CheckBox("开启工作提醒");
        reminderEnabledCheckBox.setStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 13px;
                """);
        reminderEnabledCheckBox.setOnAction(event ->
                enabledChangeHandler.accept(reminderEnabledCheckBox.isSelected())
        );

        intervalSlider = new Slider(MIN_MINUTES, MAX_MINUTES, MAX_MINUTES);
        intervalSlider.setShowTickLabels(true);
        intervalSlider.setShowTickMarks(true);
        intervalSlider.setMajorTickUnit(30);
        intervalSlider.setMinorTickCount(5);
        intervalSlider.setBlockIncrement(MINUTE_STEP);
        intervalSlider.setSnapToTicks(true);
        intervalSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int roundedMinutes = normalizeMinutes(newValue.intValue());
            selectedIntervalLabel.setText(formatMinutes(roundedMinutes));
        });

        VBox root = new VBox(12);
        root.setPadding(new Insets(18, 18, 16, 18));
        root.setStyle("""
                -fx-background-color: rgba(8, 12, 18, 0.95);
                -fx-border-color: rgba(58, 174, 210, 0.90);
                -fx-border-width: 1;
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                """);
        root.getChildren().addAll(
                titleLabel,
                reminderEnabledCheckBox,
                selectedIntervalLabel,
                intervalSlider,
                createPresetBar(),
                createActionBar()
        );
        return root;
    }

    private HBox createPresetBar() {
        HBox presetBar = new HBox(8);
        presetBar.setAlignment(Pos.CENTER_LEFT);
        presetBar.getChildren().addAll(
                createPresetButton("5m", 5),
                createPresetButton("25m", 25),
                createPresetButton("50m", 50),
                createPresetButton("2h", 120)
        );
        return presetBar;
    }

    private Button createPresetButton(String text, int minutes) {
        Button button = new Button(text);
        button.setMinWidth(48);
        button.setStyle("""
                -fx-background-color: rgba(18, 42, 56, 0.96);
                -fx-border-color: #30586c;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                -fx-font-size: 12px;
                -fx-padding: 4 8 4 8;
                """);
        button.setOnAction(event -> setSelectedMinutes(minutes));
        return button;
    }

    private void moveNearAnchor(Rectangle anchorBounds) {
        double x = anchorBounds.x - WINDOW_WIDTH - 12;
        double y = anchorBounds.y + Math.max(0, (anchorBounds.height - WINDOW_HEIGHT) / 2.0);

        Screen screen = screenFor(anchorBounds);
        if (screen != null) {
            javafx.geometry.Rectangle2D visualBounds = screen.getVisualBounds();
            double minX = visualBounds.getMinX();
            double maxX = visualBounds.getMaxX() - WINDOW_WIDTH;
            double minY = visualBounds.getMinY();
            double maxY = visualBounds.getMaxY() - WINDOW_HEIGHT;

            if (x < minX) {
                x = anchorBounds.x + anchorBounds.width + 12;
            }

            x = Math.max(minX, Math.min(x, maxX));
            y = Math.max(minY, Math.min(y, maxY));
        }

        stage.setX(x);
        stage.setY(y);
    }

    private Screen screenFor(Rectangle anchorBounds) {
        java.util.List<Screen> screens = Screen.getScreensForRectangle(
                anchorBounds.x,
                anchorBounds.y,
                anchorBounds.width,
                anchorBounds.height
        );
        if (screens.isEmpty()) {
            return Screen.getPrimary();
        }

        return screens.getFirst();
    }

    private HBox createActionBar() {
        Button restartButton = new Button("重新计时");
        restartButton.setStyle(secondaryButtonStyle());
        restartButton.setOnAction(event -> {
            reminderEnabledCheckBox.setSelected(true);
            restartHandler.run();
        });

        Button cancelButton = new Button("取消");
        cancelButton.setStyle(secondaryButtonStyle());
        cancelButton.setOnAction(event -> stage.hide());

        Button applyButton = new Button("应用");
        applyButton.setStyle(primaryButtonStyle());
        applyButton.setOnAction(event -> applySelectedInterval());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actionBar = new HBox(8, restartButton, spacer, cancelButton, applyButton);
        actionBar.setAlignment(Pos.CENTER_RIGHT);
        return actionBar;
    }

    private void applySelectedInterval() {
        applyHandler.accept(Duration.ofMinutes(selectedMinutes()));
        stage.hide();
    }

    private int selectedMinutes() {
        return normalizeMinutes((int) Math.round(intervalSlider.getValue()));
    }

    private void setSelectedMinutes(int minutes) {
        int normalizedMinutes = normalizeMinutes(minutes);
        intervalSlider.setValue(normalizedMinutes);
        selectedIntervalLabel.setText(formatMinutes(normalizedMinutes));
    }

    private static int normalizeMinutes(int minutes) {
        int clampedMinutes = Math.max(MIN_MINUTES, Math.min(minutes, MAX_MINUTES));
        return Math.round((float) clampedMinutes / MINUTE_STEP) * MINUTE_STEP;
    }

    private static String formatMinutes(int minutes) {
        if (minutes == 120) {
            return "提醒间隔：2 小时";
        }
        if (minutes >= 60 && minutes % 60 == 0) {
            return "提醒间隔：" + (minutes / 60) + " 小时";
        }

        return "提醒间隔：" + minutes + " 分钟";
    }

    private static String primaryButtonStyle() {
        return """
                -fx-background-color: rgba(18, 58, 74, 0.98);
                -fx-border-color: #3ad6ff;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                -fx-font-size: 13px;
                -fx-padding: 5 14 5 14;
                """;
    }

    private static String secondaryButtonStyle() {
        return """
                -fx-background-color: rgba(14, 22, 32, 0.92);
                -fx-border-color: #30586c;
                -fx-border-width: 1;
                -fx-text-fill: #93b7c9;
                -fx-font-size: 13px;
                -fx-padding: 5 14 5 14;
                """;
    }
}
