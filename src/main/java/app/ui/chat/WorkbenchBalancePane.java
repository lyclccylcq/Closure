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
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

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
    private final TextField balanceInput = new TextField();
    private final VBox fundsForm = new VBox(6);
    private final Button syncFunds = new Button("同步余额");
    private final Button clearFunds = new Button("清除同步");
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
        balanceInput.setId("workbench-funds-input"); balanceInput.getStyleClass().add("workbench-admin-input");
        balanceInput.setPromptText("充值后官方账单中的当前余额（USD）"); balanceInput.setMinWidth(0);
        syncFunds.setId("workbench-funds-sync"); clearFunds.setId("workbench-funds-clear");
        syncFunds.getStyleClass().add("workbench-action"); clearFunds.getStyleClass().add("workbench-action");
        HBox fundsActions = new HBox(8, balanceInput, syncFunds, clearFunds); HBox.setHgrow(balanceInput, Priority.ALWAYS);
        Label fundsHint = new Label("消费按 UTC 日统计；同步后按新增消耗估算余额，官方统计可能延迟。");
        fundsHint.setWrapText(true); fundsHint.getStyleClass().add("workbench-path");
        fundsForm.getChildren().addAll(fundsActions, fundsHint);
        fundsForm.setVisible(false); fundsForm.managedProperty().bind(fundsForm.visibleProperty());
        syncFunds.setOnAction(event -> syncFunds()); balanceInput.setOnAction(event -> syncFunds());
        clearFunds.setOnAction(event -> {
            cancel();
            try {
                balances.clearSyncedOpenAiBalance(); balanceInput.clear();
                if (report != null) { report = report.withFunding(null); result.setText(report.summaryText()); }
                updated.setText("余额同步记录已清除");
            } catch (BalanceException invalid) { updated.setText(invalid.getMessage()); }
        });
        getChildren().addAll(heading, adminForm, result, fundsForm, actions);
        setVisible(false); managedProperty().bind(visibleProperty());
    }

    void query() { request(null); }

    private void syncFunds() {
        if (closed || pending != null) return;
        try { request(new BigDecimal(balanceInput.getText().trim())); }
        catch (NumberFormatException invalid) { updated.setText("请填写官方账单中的当前美元余额。"); balanceInput.requestFocus(); }
    }

    private void request(BigDecimal calibration) {
        if (closed || pending != null) return;
        long version = ++epoch;
        long speechVersion = speech == null ? 0 : speech.speechRequestVersion();
        report = null; working(true); read.setDisable(true);
        title.setText("当前对话 API");
        result.setText("博士，等我翻一下今天的账本。"); updated.setText(calibration == null ? "正在查询…" : "正在同步余额…");
        thinking = activity.start();
        try { pending = calibration == null ? balances.query() : balances.syncOpenAiBalance(calibration); }
        catch (RuntimeException invalid) { pending = CompletableFuture.failedFuture(invalid); }
        pending.whenComplete((value, error) -> Platform.runLater(() -> {
            if (closed || version != epoch) return;
            pending = null;
            if (thinking != null) { thinking.close(); thinking = null; }
            working(false);
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
            result.setText(value.summaryText());
            fundsForm.setVisible(value.isUsageReport() && balances.canSyncOpenAiBalance());
            if (calibration != null) balanceInput.clear();
            updated.setText((calibration == null ? "查询于 " : "余额已同步 · ") + DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
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
            SpeechEmotion emotion = report.emptyFunds() ? SpeechEmotion.SERIOUS : report.lowFunds() ? SpeechEmotion.TEASING : SpeechEmotion.NEUTRAL;
            AssistantReply reply = new AssistantReply(text, text, emotion);
            if (automatic) speechSession.speakIfUnchanged(reply, version);
            else speechSession.speak(reply);
        }
    }

    void cancel() {
        epoch++;
        if (pending != null) { pending.cancel(true); pending = null; updated.setText("查询已取消"); }
        if (thinking != null) { thinking.close(); thinking = null; }
        working(false);
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
        fundsForm.setVisible(false); balanceInput.clear();
    }

    private void working(boolean value) {
        refresh.setDisable(value); syncFunds.setDisable(value); clearFunds.setDisable(value); balanceInput.setDisable(value);
    }

    void close() {
        closed = true; cancel(); activity.close(); adminInput.setText(""); balanceInput.clear(); balances.close();
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
