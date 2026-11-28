package app.ui.chat;

import app.billing.BalanceException;
import app.billing.BalanceReport;
import app.billing.BalanceService;
import app.character.CharacterActivityListener;
import app.character.ChatActivity;
import app.chat.model.AssistantReply;
import app.service.ChatSpeechController;
import app.voice.model.SpeechEmotion;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** On-demand account tool, independent of conversation history and the language model. */
final class WorkbenchBalancePane extends VBox {
    private final BalanceService balances;
    private final ChatSpeechController speech;
    private final ChatSpeechController.SpeechSession speechSession;
    private final ChatActivity activity;
    private final Consumer<String> bubble;
    private final Label title = new Label("当前对话 API");
    private final Label result = new Label("查询当前模型连接的余额，由可露汇报。");
    private final Label updated = new Label();
    private final Button refresh = new Button("查询并播报");
    private final Button read = new Button("再报一次");
    private CompletableFuture<BalanceReport> pending;
    private ChatActivity.Request thinking;
    private BalanceReport report;
    private boolean closed;
    private long epoch;

    WorkbenchBalancePane(BalanceService balances, ChatSpeechController speech,
                         CharacterActivityListener listener, Consumer<String> bubble) {
        super(6);
        this.balances = balances; this.speech = speech; this.bubble = bubble;
        speechSession = speech == null ? null : speech.createSession();
        activity = new ChatActivity(listener);
        setId("workbench-balance-pane"); getStyleClass().add("workbench-balance");
        title.getStyleClass().add("workbench-conversation-title");
        result.setId("workbench-balance-result"); result.setWrapText(true); result.setMinWidth(0);
        result.setMaxWidth(Double.MAX_VALUE);
        updated.setId("workbench-balance-updated"); updated.getStyleClass().add("workbench-path");
        refresh.setId("workbench-balance-refresh"); refresh.getStyleClass().add("workbench-action");
        read.setId("workbench-balance-read"); read.getStyleClass().add("workbench-action"); read.setDisable(true);
        refresh.setOnAction(event -> query()); read.setOnAction(event -> speak(false, 0));
        HBox actions = new HBox(8, updated, refresh, read); actions.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(updated, Priority.ALWAYS); updated.setMaxWidth(Double.MAX_VALUE); updated.setMinWidth(0);
        getChildren().addAll(title, result, actions);
        setVisible(false); managedProperty().bind(visibleProperty());
    }

    void query() {
        if (closed || pending != null) return;
        long version = ++epoch;
        long speechVersion = speech == null ? 0 : speech.speechRequestVersion();
        report = null; refresh.setDisable(true); read.setDisable(true);
        title.setText("当前对话 API");
        result.setText("博士，等我看一眼当前 API 的余额。"); updated.setText("正在查询…");
        thinking = activity.start();
        try { pending = balances.query(); }
        catch (RuntimeException invalid) { pending = CompletableFuture.failedFuture(invalid); }
        pending.whenComplete((value, error) -> Platform.runLater(() -> {
            if (closed || version != epoch) return;
            pending = null;
            if (thinking != null) { thinking.close(); thinking = null; }
            refresh.setDisable(false);
            if (error != null || value == null) {
                result.setText(message(error)); updated.setText("尚未取得余额"); return;
            }
            report = value;
            title.setText(value.platform());
            result.setText(value.lines().stream().map(BalanceReport.Line::text).collect(Collectors.joining("\n"))
                    + (value.note().isBlank() ? "" : "\n" + value.note()));
            updated.setText("查询于 " + DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault()).format(value.checkedAt()));
            read.setDisable(speechSession == null);
            speak(true, speechVersion);
        }));
    }

    private void speak(boolean automatic, long version) {
        if (closed || report == null || !isVisible()) return;
        if (automatic && speech != null && speech.speechRequestVersion() != version) return;
        String text = report.speechText();
        bubble.accept(text);
        if (speechSession != null) {
            AssistantReply reply = new AssistantReply(text, text, SpeechEmotion.NEUTRAL);
            if (automatic) speechSession.speakIfUnchanged(reply, version);
            else speechSession.speak(reply);
        }
    }

    void cancel() {
        epoch++;
        if (pending != null) { pending.cancel(true); pending = null; updated.setText("查询已取消"); }
        if (thinking != null) { thinking.close(); thinking = null; }
        refresh.setDisable(false);
        if (speechSession != null) speechSession.stop();
    }

    void close() { closed = true; cancel(); activity.close(); }

    private static String message(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error instanceof BalanceException ? error.getMessage() : "余额暂时没查到，稍后再试一下吧。";
    }
}
