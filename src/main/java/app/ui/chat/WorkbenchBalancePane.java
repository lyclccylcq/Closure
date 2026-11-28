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
import javafx.scene.control.PasswordField;
import javafx.scene.control.Hyperlink;
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
    private final Label result = new Label("查询当前 API 的费用、用量或余额，由可露汇报。");
    private final Label updated = new Label();
    private final Button refresh = new Button("查询并播报");
    private final Button read = new Button("再报一次");
    private final PasswordField adminInput = new PasswordField();
    private final VBox adminForm = new VBox(6);
    private final Button adminSettings = new Button("管理密钥");
    private final Hyperlink billingPage = new Hyperlink("OpenAI 账单");
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
        adminInput.setId("workbench-admin-key");
        adminInput.getStyleClass().add("workbench-admin-input");
        adminInput.setPromptText("粘贴 OpenAI Admin API key"); adminInput.setMinWidth(0);
        Button apply = new Button("应用并查询"); apply.setId("workbench-admin-apply");
        Button clear = new Button("清除管理密钥"); clear.setId("workbench-admin-clear");
        for (Button button : new Button[]{adminSettings, apply, clear}) button.getStyleClass().add("workbench-action");
        Label hint = new Label("仅本次运行使用，退出后需重新填写。只用于 OpenAI 官方费用与用量。");
        hint.setWrapText(true); hint.getStyleClass().add("workbench-path");
        HBox keyActions = new HBox(8, apply, clear);
        adminForm.getChildren().addAll(adminInput, keyActions, hint);
        adminForm.setVisible(false); adminForm.managedProperty().bind(adminForm.visibleProperty());
        apply.setOnAction(event -> applyAdminKey()); adminInput.setOnAction(event -> applyAdminKey());
        clear.setOnAction(event -> {
            cancel(); balances.clearOpenAiAdminKey(); adminInput.setText(""); resetReport();
            result.setText("本次运行的管理密钥已清除，普通对话连接保持可用。");
            adminForm.setVisible(true);
        });
        adminSettings.setId("workbench-admin-settings"); adminSettings.setVisible(false);
        adminSettings.managedProperty().bind(adminSettings.visibleProperty());
        adminSettings.setOnAction(event -> {
            adminForm.setVisible(!adminForm.isVisible());
            if (adminForm.isVisible()) adminInput.requestFocus(); else adminInput.setText("");
        });
        billingPage.setId("workbench-openai-billing"); billingPage.setVisible(false);
        billingPage.managedProperty().bind(billingPage.visibleProperty());
        billingPage.setOnAction(event -> javax.swing.SwingUtilities.invokeLater(() -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://platform.openai.com/settings/organization/billing/overview")); }
            catch (Exception unavailable) { Platform.runLater(() -> updated.setText("请在浏览器打开 OpenAI 官方账单页。")); }
        }));
        title.setMinWidth(0); title.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(title, Priority.ALWAYS);
        HBox heading = new HBox(8, title, billingPage, adminSettings); heading.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(heading, adminForm, result, actions);
        setVisible(false); managedProperty().bind(visibleProperty());
    }

    void query() {
        if (closed || pending != null) return;
        long version = ++epoch;
        long speechVersion = speech == null ? 0 : speech.speechRequestVersion();
        report = null; refresh.setDisable(true); read.setDisable(true);
        title.setText("当前对话 API");
        result.setText("博士，等我看一眼当前 API 的账户信息。"); updated.setText("正在查询…");
        thinking = activity.start();
        try { pending = balances.query(); }
        catch (RuntimeException invalid) { pending = CompletableFuture.failedFuture(invalid); }
        pending.whenComplete((value, error) -> Platform.runLater(() -> {
            if (closed || version != epoch) return;
            pending = null;
            if (thinking != null) { thinking.close(); thinking = null; }
            refresh.setDisable(false);
            if (error != null || value == null) {
                result.setText(message(error)); updated.setText("尚未取得账户数据");
                Throwable cause = unwrap(error);
                if (cause instanceof BalanceException failure && failure.action() == BalanceException.Action.CONFIGURE_OPENAI_ADMIN) {
                    showAdminConfiguration(); adminForm.setVisible(true); adminInput.requestFocus();
                }
                return;
            }
            report = value;
            if (value.isUsageReport()) showAdminConfiguration();
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

    private void applyAdminKey() {
        if (closed || !balances.canConfigureOpenAiAdminKey()) return;
        cancel(); resetReport();
        try {
            balances.configureOpenAiAdminKey(adminInput.getText());
            adminInput.setText(""); adminForm.setVisible(false); query();
        } catch (BalanceException invalid) { result.setText(invalid.getMessage()); }
    }

    private void showAdminConfiguration() {
        billingPage.setVisible(true);
        adminSettings.setVisible(balances.canConfigureOpenAiAdminKey());
    }

    private void resetReport() {
        report = null; read.setDisable(true); title.setText("当前对话 API"); updated.setText("尚未查询");
    }

    void close() {
        closed = true; cancel(); activity.close(); adminInput.setText(""); balances.close();
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    private static String message(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof BalanceException ? cause.getMessage() : "账户信息暂时没查到，稍后再试一下吧。";
    }
}
