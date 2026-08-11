package app.ui.reminder;

import app.reminder.CourseScheduleEntry;
import app.ui.UiFonts;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

public final class CourseScheduleWindow {
    private static final Object FX_START_LOCK = new Object();
    private static final int WINDOW_WIDTH = 440;
    private static final int WINDOW_HEIGHT = 360;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static boolean fxStarted;

    private final Function<Path, List<CourseScheduleEntry>> importHandler;
    private final Consumer<String> statusHandler;
    private final Runnable testReminderHandler;
    private Stage stage;
    private Label statusLabel;
    private ListView<String> scheduleListView;

    public CourseScheduleWindow(
            Function<Path, List<CourseScheduleEntry>> importHandler,
            Consumer<String> statusHandler,
            Runnable testReminderHandler
    ) {
        this.importHandler = importHandler;
        this.statusHandler = statusHandler;
        this.testReminderHandler = testReminderHandler;
        runOnFxThread(this::initStage);
    }

    public void show(List<CourseScheduleEntry> entries, Rectangle anchorBounds) {
        Rectangle bounds = new Rectangle(anchorBounds);
        runOnFxThread(() -> {
            ensureStage();
            updateEntries(entries);
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
        stage.setTitle("课程表提醒");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);

        Scene scene = new Scene(createContent(), WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    private VBox createContent() {
        Label titleLabel = new Label("课程表提醒");
        titleLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 16px;
                -fx-font-weight: bold;
                """));
        UiFonts.applyJavaFxFont(titleLabel,16);

        statusLabel = new Label("支持 CSV/TXT/TSV/ICS/XLSX，可带表头，也可每行写：时间 | 标题 | 类型 | 提前分钟 | 地点");
        statusLabel.setWrapText(true);
        statusLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #93b7c9;
                -fx-font-size: 12px;
                """));
        UiFonts.applyJavaFxFont(statusLabel,12);

        scheduleListView = new ListView<>();
        Label emptyScheduleLabel = new Label("还没有课程表。");
        emptyScheduleLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #93b7c9;
                -fx-font-size: 13px;
                """));
        UiFonts.applyJavaFxFont(emptyScheduleLabel,13);
        scheduleListView.setPlaceholder(emptyScheduleLabel);
        scheduleListView.setStyle(UiFonts.fxStyle("""
                -fx-control-inner-background: rgba(14, 22, 32, 0.96);
                -fx-background-color: rgba(14, 22, 32, 0.96);
                -fx-border-color: #30586c;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                """));
        VBox.setVgrow(scheduleListView, Priority.ALWAYS);

        VBox root = new VBox(12);
        root.setPadding(new Insets(18, 18, 16, 18));
        root.setStyle(UiFonts.fxStyle("""
                -fx-background-color: rgba(8, 12, 18, 0.95);
                -fx-border-color: rgba(58, 174, 210, 0.90);
                -fx-border-width: 1;
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                """));
        root.getChildren().addAll(titleLabel, statusLabel, scheduleListView, createActionBar());
        return root;
    }

    private HBox createActionBar() {
        Button testButton = new Button("测试提醒");
        testButton.setStyle(secondaryButtonStyle());
        UiFonts.applyJavaFxFont(testButton,13);
        testButton.setOnAction(event -> testReminderHandler.run());

        Button importButton = new Button("上传课程表");
        importButton.setStyle(primaryButtonStyle());
        UiFonts.applyJavaFxFont(importButton,13);
        importButton.setOnAction(event -> chooseAndImportFile());

        Button closeButton = new Button("关闭");
        closeButton.setStyle(secondaryButtonStyle());
        UiFonts.applyJavaFxFont(closeButton,13);
        closeButton.setOnAction(event -> stage.hide());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actionBar = new HBox(8, testButton, importButton, spacer, closeButton);
        actionBar.setAlignment(Pos.CENTER_RIGHT);
        return actionBar;
    }

    private void chooseAndImportFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择课程表");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("课程表文件", "*.csv", "*.txt", "*.tsv", "*.ics", "*.ical", "*.xlsx"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        File selectedFile = fileChooser.showOpenDialog(stage);
        if (selectedFile == null) {
            return;
        }

        statusLabel.setText("正在导入课程表，格式不标准时会尝试交给 AI 转换...");
        CompletableFuture
                .supplyAsync(() -> importHandler.apply(selectedFile.toPath()))
                .whenComplete((importedEntries, error) -> runOnFxThread(() -> handleImportResult(importedEntries, error)));
    }

    private void handleImportResult(List<CourseScheduleEntry> importedEntries, Throwable error) {
        if (error != null) {
            String message = "课程表导入失败：" + rootErrorMessage(error);
            statusLabel.setText(message);
            statusHandler.accept(message);
            return;
        }

        updateEntries(importedEntries);
        String message = "已保存 " + importedEntries.size() + " 条课程/Deadline。";
        statusLabel.setText(message);
        statusHandler.accept(message);
    }

    private static String rootErrorMessage(Throwable error) {
        Throwable currentError = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        while ((currentError.getMessage() == null || currentError.getMessage().isBlank())
                && currentError.getCause() != null
                && currentError.getCause() != currentError) {
            currentError = currentError.getCause();
        }

        return currentError.getMessage() == null ? currentError.getClass().getSimpleName() : currentError.getMessage();
    }

    private void updateEntries(List<CourseScheduleEntry> entries) {
        scheduleListView.getItems().setAll(entries.stream()
                .sorted(java.util.Comparator.comparing(CourseScheduleEntry::startsAt))
                .map(this::formatEntry)
                .toList());
        if (entries.isEmpty()) {
            statusLabel.setText("还没有保存课程表。");
        } else {
            statusLabel.setText("已保存 " + entries.size() + " 条课程/Deadline。");
        }
    }

    private String formatEntry(CourseScheduleEntry entry) {
        String locationText = entry.location().isBlank() ? "" : " @ " + entry.location();
        return entry.startsAt().format(DATE_TIME_FORMATTER)
                + "  " + entry.type().displayName()
                + "  " + entry.title()
                + locationText
                + "  提前 " + entry.remindBefore().toMinutes() + " 分钟";
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

    private static String primaryButtonStyle() {
        return UiFonts.fxStyle("""
                -fx-background-color: rgba(18, 58, 74, 0.98);
                -fx-border-color: #3ad6ff;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                -fx-font-size: 13px;
                -fx-padding: 5 14 5 14;
                """);
    }

    private static String secondaryButtonStyle() {
        return UiFonts.fxStyle("""
                -fx-background-color: rgba(14, 22, 32, 0.92);
                -fx-border-color: #30586c;
                -fx-border-width: 1;
                -fx-text-fill: #93b7c9;
                -fx-font-size: 13px;
                -fx-padding: 5 14 5 14;
                """);
    }
}
