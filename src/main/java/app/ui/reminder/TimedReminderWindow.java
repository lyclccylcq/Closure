package app.ui.reminder;

import app.reminder.EventReminderEntry;
import app.reminder.WorkBreakReminder;
import app.ui.UiFonts;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.StringConverter;

import java.awt.Rectangle;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class TimedReminderWindow {
    private static final Object FX_START_LOCK = new Object();
    private static final int WINDOW_WIDTH = 460;
    private static final int WINDOW_HEIGHT = 560;
    private static final int MIN_MINUTES = (int) WorkBreakReminder.MIN_WORK_INTERVAL.toMinutes();
    private static final int MAX_MINUTES = (int) WorkBreakReminder.MAX_WORK_INTERVAL.toMinutes();
    private static final int MINUTE_STEP = 5;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private static boolean fxStarted;

    private final Consumer<Duration> applyWorkIntervalHandler;
    private final Consumer<Boolean> workEnabledChangeHandler;
    private final Runnable workRestartHandler;
    private final EventReminderCreateHandler eventReminderCreateHandler;
    private final Consumer<String> statusHandler;
    private Stage stage;
    private Label statusLabel;
    private Label selectedIntervalLabel;
    private CheckBox workReminderEnabledCheckBox;
    private Slider workIntervalSlider;
    private TextArea eventTextArea;
    private Spinner<Integer> hourSpinner;
    private Spinner<Integer> minuteSpinner;
    private Button createEventButton;
    private ListView<String> eventReminderListView;

    public TimedReminderWindow(
            Consumer<Duration> applyWorkIntervalHandler,
            Consumer<Boolean> workEnabledChangeHandler,
            Runnable workRestartHandler,
            EventReminderCreateHandler eventReminderCreateHandler,
            Consumer<String> statusHandler
    ) {
        this.applyWorkIntervalHandler = applyWorkIntervalHandler;
        this.workEnabledChangeHandler = workEnabledChangeHandler;
        this.workRestartHandler = workRestartHandler;
        this.eventReminderCreateHandler = eventReminderCreateHandler;
        this.statusHandler = statusHandler;
        runOnFxThread(this::initStage);
    }

    public void show(
            Duration currentWorkInterval,
            boolean workReminderEnabled,
            List<EventReminderEntry> eventReminderEntries,
            Rectangle anchorBounds
    ) {
        int currentMinutes = normalizeMinutes((int) currentWorkInterval.toMinutes());
        Rectangle bounds = new Rectangle(anchorBounds);
        runOnFxThread(() -> {
            ensureStage();
            setSelectedWorkMinutes(currentMinutes);
            workReminderEnabledCheckBox.setSelected(workReminderEnabled);
            setDefaultEventTime();
            updateEventReminderEntries(eventReminderEntries);
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
        stage.setTitle("定时提醒");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);

        Scene scene = new Scene(createContent(), WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    private VBox createContent() {
        Label titleLabel = new Label("定时提醒");
        titleLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 16px;
                -fx-font-weight: bold;
                """));
        UiFonts.applyJavaFxFont(titleLabel,16);

        statusLabel = new Label("设置工作休息提醒，或给今天添加一个事件提醒。");
        statusLabel.setWrapText(true);
        statusLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #93b7c9;
                -fx-font-size: 12px;
                """));
        UiFonts.applyJavaFxFont(statusLabel,12);

        VBox root = new VBox(12);
        root.setPadding(new Insets(18, 18, 16, 18));
        root.setStyle(UiFonts.fxStyle("""
                -fx-background-color: rgba(8, 12, 18, 0.95);
                -fx-border-color: rgba(58, 174, 210, 0.90);
                -fx-border-width: 1;
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                """));
        root.getChildren().addAll(
                titleLabel,
                statusLabel,
                createWorkReminderSection(),
                createEventReminderSection(),
                createFooter()
        );
        return root;
    }

    private VBox createWorkReminderSection() {
        Label sectionLabel = sectionLabel("工作时间提醒");

        selectedIntervalLabel = new Label();
        selectedIntervalLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #93b7c9;
                -fx-font-size: 13px;
                """));
        UiFonts.applyJavaFxFont(selectedIntervalLabel,13);

        workReminderEnabledCheckBox = new CheckBox("开启到点休息提醒");
        workReminderEnabledCheckBox.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 13px;
                """));
        UiFonts.applyJavaFxFont(workReminderEnabledCheckBox,13);
        workReminderEnabledCheckBox.setOnAction(event ->
                workEnabledChangeHandler.accept(workReminderEnabledCheckBox.isSelected())
        );

        workIntervalSlider = new Slider(MIN_MINUTES, MAX_MINUTES, 60);
        workIntervalSlider.setShowTickLabels(true);
        workIntervalSlider.setShowTickMarks(true);
        workIntervalSlider.setMajorTickUnit(30);
        workIntervalSlider.setMinorTickCount(5);
        workIntervalSlider.setBlockIncrement(MINUTE_STEP);
        workIntervalSlider.setSnapToTicks(true);
        workIntervalSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int roundedMinutes = normalizeMinutes(newValue.intValue());
            selectedIntervalLabel.setText(formatWorkMinutes(roundedMinutes));
        });

        HBox actionBar = new HBox(8);
        actionBar.setAlignment(Pos.CENTER_RIGHT);
        actionBar.getChildren().addAll(
                createPresetButton("25m", 25),
                createPresetButton("50m", 50),
                createPresetButton("1h", 60),
                createPresetButton("2h", 120),
                spacer(),
                workButton("重新计时", event -> {
                    workReminderEnabledCheckBox.setSelected(true);
                    workRestartHandler.run();
                }),
                primaryWorkButton("应用间隔", event -> applySelectedWorkInterval())
        );

        VBox section = new VBox(8, sectionLabel, workReminderEnabledCheckBox, selectedIntervalLabel, workIntervalSlider, actionBar);
        VBox.setVgrow(section, Priority.NEVER);
        return section;
    }

    private VBox createEventReminderSection() {
        Label sectionLabel = sectionLabel("今天事件提醒");

        eventTextArea = new TextArea();
        eventTextArea.setPromptText("例如：提交 COMP6710 作业");
        eventTextArea.setPrefRowCount(2);
        eventTextArea.setWrapText(true);
        eventTextArea.setStyle(inputStyle());
        UiFonts.applyJavaFxFont(eventTextArea,13);

        hourSpinner = createTimeSpinner(0, 23, 9, 1);
        minuteSpinner = createTimeSpinner(0, 59, 0, 1);
        HBox timeSelector = new HBox(8,
                smallLabel("今天"),
                hourSpinner,
                smallLabel("时"),
                minuteSpinner,
                smallLabel("分")
        );
        timeSelector.setAlignment(Pos.CENTER_LEFT);

        createEventButton = new Button("创建事件提醒");
        createEventButton.setStyle(primaryButtonStyle());
        UiFonts.applyJavaFxFont(createEventButton,13);
        createEventButton.setOnAction(event -> createEventReminder());

        HBox createBar = new HBox(8, timeSelector, spacer(), createEventButton);
        createBar.setAlignment(Pos.CENTER_RIGHT);

        eventReminderListView = new ListView<>();
        Label emptyReminderLabel = new Label("今天还没有事件提醒。");
        emptyReminderLabel.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #93b7c9;
                -fx-font-size: 13px;
                """));
        UiFonts.applyJavaFxFont(emptyReminderLabel,13);
        eventReminderListView.setPlaceholder(emptyReminderLabel);
        eventReminderListView.setStyle(UiFonts.fxStyle("""
                -fx-control-inner-background: rgba(14, 22, 32, 0.96);
                -fx-background-color: rgba(14, 22, 32, 0.96);
                -fx-border-color: #30586c;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                """));
        VBox.setVgrow(eventReminderListView, Priority.ALWAYS);

        VBox section = new VBox(8, sectionLabel, eventTextArea, createBar, eventReminderListView);
        VBox.setVgrow(section, Priority.ALWAYS);
        return section;
    }

    private HBox createFooter() {
        Button closeButton = new Button("关闭");
        closeButton.setStyle(secondaryButtonStyle());
        UiFonts.applyJavaFxFont(closeButton,13);
        closeButton.setOnAction(event -> stage.hide());

        HBox footer = new HBox(8, spacer(), closeButton);
        footer.setAlignment(Pos.CENTER_RIGHT);
        return footer;
    }

    private void createEventReminder() {
        commitEventTimeEditors();
        String eventText = eventTextArea.getText();
        int hour = hourSpinner.getValue();
        int minute = minuteSpinner.getValue();
        createEventButton.setDisable(true);
        statusLabel.setText("正在生成事件提醒文案...");

        eventReminderCreateHandler.create(eventText, hour, minute)
                .whenComplete((entries, error) -> runOnFxThread(() -> {
                    createEventButton.setDisable(false);
                    if (error != null) {
                        String message = "事件提醒保存失败：" + rootErrorMessage(error);
                        statusLabel.setText(message);
                        statusHandler.accept(message);
                        return;
                    }

                    updateEventReminderEntries(entries);
                    eventTextArea.clear();
                    String message = "已保存 " + entries.size() + " 条今天事件提醒。";
                    statusLabel.setText(message);
                    statusHandler.accept(message);
                }));
    }

    private void applySelectedWorkInterval() {
        applyWorkIntervalHandler.accept(Duration.ofMinutes(selectedWorkMinutes()));
        statusLabel.setText("工作提醒间隔已更新。");
    }

    private void updateEventReminderEntries(List<EventReminderEntry> entries) {
        eventReminderListView.getItems().setAll(entries.stream()
                .sorted(java.util.Comparator.comparing(EventReminderEntry::remindAt))
                .map(this::formatEventEntry)
                .toList());
    }

    private String formatEventEntry(EventReminderEntry entry) {
        return entry.remindAt().format(TIME_FORMATTER)
                + "  " + entry.eventText()
                + "\n"
                + entry.reminderText();
    }

    private int selectedWorkMinutes() {
        return normalizeMinutes((int) Math.round(workIntervalSlider.getValue()));
    }

    private void setSelectedWorkMinutes(int minutes) {
        int normalizedMinutes = normalizeMinutes(minutes);
        workIntervalSlider.setValue(normalizedMinutes);
        selectedIntervalLabel.setText(formatWorkMinutes(normalizedMinutes));
    }

    private void setDefaultEventTime() {
        LocalTime time = roundedUpcomingTime();
        hourSpinner.getValueFactory().setValue(time.getHour());
        minuteSpinner.getValueFactory().setValue(time.getMinute());
    }

    private LocalTime roundedUpcomingTime() {
        LocalTime now = LocalTime.now();
        LocalTime time = now.plusMinutes(10);
        if (time.isBefore(now)) {
            return LocalTime.of(23, 59);
        }

        return time.withSecond(0).withNano(0);
    }

    private Spinner<Integer> createTimeSpinner(int min, int max, int initialValue, int amountToStepBy) {
        Spinner<Integer> spinner = new Spinner<>();
        SpinnerValueFactory.IntegerSpinnerValueFactory valueFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                min,
                max,
                initialValue,
                amountToStepBy
        );
        valueFactory.setConverter(new PaddedIntegerStringConverter());
        spinner.setValueFactory(valueFactory);
        spinner.setEditable(true);
        spinner.setPrefWidth(70);
        spinner.setStyle(inputStyle());
        UiFonts.applyJavaFxFont(spinner.getEditor(),13);
        spinner.getEditor().setAlignment(Pos.CENTER);
        spinner.getEditor().setOnAction(event -> commitTimeSpinner(spinner, min, max));
        spinner.getEditor().focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused) {
                commitTimeSpinner(spinner, min, max);
            }
        });
        spinner.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() > 0) {
                spinner.increment();
            } else if (event.getDeltaY() < 0) {
                spinner.decrement();
            }
            event.consume();
        });
        return spinner;
    }

    private void commitEventTimeEditors() {
        commitTimeSpinner(hourSpinner, 0, 23);
        commitTimeSpinner(minuteSpinner, 0, 59);
    }

    private void commitTimeSpinner(Spinner<Integer> spinner, int min, int max) {
        String editorText = spinner.getEditor().getText();
        SpinnerValueFactory<Integer> valueFactory = spinner.getValueFactory();
        try {
            int parsedValue = Integer.parseInt(editorText.trim());
            valueFactory.setValue(Math.max(min, Math.min(parsedValue, max)));
        } catch (NumberFormatException exception) {
            valueFactory.setValue(spinner.getValue());
        }
        spinner.getEditor().setText(valueFactory.getConverter().toString(valueFactory.getValue()));
    }

    private Button createPresetButton(String text, int minutes) {
        Button button = workButton(text, event -> setSelectedWorkMinutes(minutes));
        button.setMinWidth(44);
        return button;
    }

    private Button workButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.setStyle(secondaryButtonStyle());
        UiFonts.applyJavaFxFont(button,13);
        button.setOnAction(handler);
        return button;
    }

    private Button primaryWorkButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button button = new Button(text);
        button.setStyle(primaryButtonStyle());
        UiFonts.applyJavaFxFont(button,13);
        button.setOnAction(handler);
        return button;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 14px;
                -fx-font-weight: bold;
                """));
        UiFonts.applyJavaFxFont(label,14);
        return label;
    }

    private Label smallLabel(String text) {
        Label label = new Label(text);
        label.setStyle(UiFonts.fxStyle("""
                -fx-text-fill: #93b7c9;
                -fx-font-size: 13px;
                """));
        UiFonts.applyJavaFxFont(label,13);
        return label;
    }

    private Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
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

    private static int normalizeMinutes(int minutes) {
        int clampedMinutes = Math.max(MIN_MINUTES, Math.min(minutes, MAX_MINUTES));
        return Math.round((float) clampedMinutes / MINUTE_STEP) * MINUTE_STEP;
    }

    private static String formatWorkMinutes(int minutes) {
        if (minutes >= 60 && minutes % 60 == 0) {
            return "休息提醒间隔：" + (minutes / 60) + " 小时";
        }

        return "休息提醒间隔：" + minutes + " 分钟";
    }

    private static String rootErrorMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String inputStyle() {
        return UiFonts.fxStyle("""
                -fx-control-inner-background: rgba(14, 22, 32, 0.96);
                -fx-background-color: rgba(14, 22, 32, 0.96);
                -fx-border-color: #30586c;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                -fx-prompt-text-fill: #5f8798;
                -fx-font-size: 13px;
                """);
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
                -fx-padding: 5 10 5 10;
                """);
    }

    @FunctionalInterface
    public interface EventReminderCreateHandler {
        CompletableFuture<List<EventReminderEntry>> create(String eventText, int hour, int minute);
    }

    private static final class PaddedIntegerStringConverter extends StringConverter<Integer> {
        @Override
        public String toString(Integer value) {
            if (value == null) {
                return "";
            }

            return String.format("%02d", value);
        }

        @Override
        public Integer fromString(String text) {
            if (text == null || text.isBlank()) {
                return 0;
            }

            return Integer.parseInt(text.trim());
        }
    }
}
