package app.ui.chat;

import app.billing.BalanceException;
import app.billing.BalanceReport;
import app.billing.BalanceService;
import app.billing.AdminKeyState;
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
    private final Button applyAdmin = new Button("保存并查询");
    private final Button clearAdmin = new Button("清除管理密钥");
    private final Label adminState = new Label();
    private final Hyperlink billingPage = new Hyperlink("OpenAI 账单");
    private final TextField balanceInput = new TextField();
    private final VBox fundsForm = new VBox(6);
    private final Button syncFunds = new Button("同步余额");
    private final Button clearFunds = new Button("清除同步");
    private CompletableFuture<?> pending;
    private CompletableFuture<AdminKeyState> pendingKeyState;
    private ChatActivity.Request thinking;
    private BalanceReport report;
    private boolean closed;
    private long epoch;
    private long keyStateEpoch;

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
        applyAdmin.setId("workbench-admin-apply"); clearAdmin.setId("workbench-admin-clear");
        applyAdmin.disableProperty().bind(adminInput.disabledProperty().or(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> adminInput.getText().isBlank(), adminInput.textProperty())));
        for (Button button : new Button[]{adminSettings, applyAdmin, clearAdmin}) button.getStyleClass().add("workbench-action");
        Label hint = new Label(balances.persistsOpenAiAdminKey()
                ? "保存到 macOS 钥匙串，之后自动读取。仅用于 OpenAI 官方费用查询。"
                : "当前系统仅本次运行使用，退出后需重新填写。仅用于 OpenAI 官方费用查询。");
        hint.setId("workbench-admin-storage");
        hint.setWrapText(true); hint.getStyleClass().add("workbench-path");
        adminState.setId("workbench-admin-state"); adminState.setWrapText(true);
        adminState.getStyleClass().add("workbench-path"); adminState.setVisible(false);
        adminState.managedProperty().bind(adminState.visibleProperty());
        HBox keyActions = new HBox(8, applyAdmin, clearAdmin);
        adminForm.getChildren().addAll(adminInput, keyActions, hint);
        adminForm.setVisible(false); adminForm.managedProperty().bind(adminForm.visibleProperty());
        applyAdmin.setOnAction(event -> changeAdminKey(false)); adminInput.setOnAction(event -> changeAdminKey(false));
        clearAdmin.setOnAction(event -> changeAdminKey(true));
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
        getChildren().addAll(heading, adminState, adminForm, result, fundsForm, actions);
        setVisible(false); managedProperty().bind(visibleProperty());
    }

    void query() { request(null); }

    private void syncFunds() {
        if (closed || pending != null) return;
        try { request(new BigDecimal(balanceInput.getText().trim())); }
        catch (NumberFormatException invalid) { updated.setText("请填写官方账单中的当前美元余额。"); balanceInput.requestFocus(); }
    }

    private void request(BigDecimal calibration) { request(calibration, speech == null ? 0 : speech.speechRequestVersion()); }

    private void request(BigDecimal calibration, long speechVersion) {
        if (closed || pending != null) return;
        cancelKeyState();
        long version = ++epoch;
        report = null; working(true); read.setDisable(true);
        title.setText("当前对话 API");
        result.setText("博士，等我翻一下今天的账本。"); updated.setText(calibration == null ? "正在查询…" : "正在同步余额…");
        thinking = activity.start();
        if (balances.canConfigureOpenAiAdminKey()) refreshAdminKeyState();
        CompletableFuture<BalanceReport> operation;
        try { operation = calibration == null ? balances.query() : balances.syncOpenAiBalance(calibration); }
        catch (RuntimeException invalid) { operation = CompletableFuture.failedFuture(invalid); }
        pending = operation;
        operation.whenComplete((value, error) -> Platform.runLater(() -> {
            if (closed || version != epoch) return;
            pending = null;
            if (thinking != null) { thinking.close(); thinking = null; }
            working(false);
            if (error != null || value == null) {
                result.setText(message(error)); updated.setText("尚未取得账户数据");
                Throwable cause = unwrap(error);
                if (cause instanceof BalanceException failure) {
                    switch (failure.action()) {
                        case CONFIGURE_OPENAI_ADMIN -> {
                            showAdminConfiguration(); refreshAdminKeyState();
                            adminForm.setVisible(true); adminInput.requestFocus();
                        }
                        case ACCESS_OPENAI_ADMIN -> {
                            cancelKeyState();
                            showAdminConfiguration(); setKeyStateText("钥匙串暂时无法读取。解锁或允许访问后，点击“查询并播报”重试。");
                        }
                        case REVIEW_OPENAI_ADMIN -> { showAdminConfiguration(); refreshAdminKeyState(); }
                        default -> { }
                    }
                }
                return;
            }
            report = value;
            if (value.isUsageReport()) {
                showAdminConfiguration();
                if (pendingKeyState == null && !adminState.isVisible()) setAdminKeyState(AdminKeyState.UNKNOWN);
            }
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
        cancelKeyState();
        if (pending != null) { pending.cancel(true); pending = null; updated.setText("查询已取消"); }
        if (thinking != null) { thinking.close(); thinking = null; }
        working(false);
        editingCredential(false);
        if (speechSession != null) speechSession.stop();
    }

    private void cancelKeyState() {
        keyStateEpoch++;
        if (pendingKeyState != null) { pendingKeyState.cancel(true); pendingKeyState = null; }
    }

    private void changeAdminKey(boolean clear) {
        if (closed || !balances.canConfigureOpenAiAdminKey()) return;
        if (!clear && adminInput.getText().isBlank()) return;
        String key = clear ? null : adminInput.getText();
        adminInput.setText("");
        cancel(); resetReport();
        long version = ++epoch;
        long speechVersion = speech == null ? 0 : speech.speechRequestVersion();
        working(true); editingCredential(true);
        setKeyStateText(clear ? "正在清除保存的管理密钥…" : "正在保存管理密钥…");
        result.setText(clear ? "正在清除管理密钥…" : "正在保存管理密钥…"); updated.setText("正在处理…");
        CompletableFuture<Void> operation;
        try { operation = clear ? balances.clearOpenAiAdminKeyAsync() : balances.configureOpenAiAdminKeyAsync(key); }
        catch (RuntimeException invalid) { operation = CompletableFuture.failedFuture(invalid); }
        pending = operation;
        operation.whenComplete((value, error) -> Platform.runLater(() -> {
            if (closed || version != epoch) return;
            pending = null; working(false); editingCredential(false);
            if (error != null) {
                result.setText(message(error)); updated.setText(clear ? "管理密钥未能清除" : "管理密钥未能保存");
                refreshAdminKeyState();
                adminForm.setVisible(true); return;
            }
            if (clear) {
                result.setText("保存的管理密钥已清除，普通对话连接保持可用。"); updated.setText("管理密钥已清除");
                setAdminKeyState(AdminKeyState.MISSING);
                adminForm.setVisible(true);
            } else {
                setAdminKeyState(balances.persistsOpenAiAdminKey() ? AdminKeyState.SAVED : AdminKeyState.SESSION);
                adminForm.setVisible(false); request(null, speechVersion);
            }
        }));
    }

    private void showAdminConfiguration() {
        billingPage.setVisible(true);
        adminSettings.setVisible(balances.canConfigureOpenAiAdminKey());
    }

    private void refreshAdminKeyState() {
        long version = ++keyStateEpoch;
        if (pendingKeyState != null) pendingKeyState.cancel(true);
        CompletableFuture<AdminKeyState> operation;
        try { operation = balances.openAiAdminKeyStateAsync(); }
        catch (RuntimeException failure) { operation = CompletableFuture.failedFuture(failure); }
        pendingKeyState = operation;
        operation.whenComplete((state, error) -> Platform.runLater(() -> {
            if (closed || version != keyStateEpoch) return;
            pendingKeyState = null;
            if (error != null) {
                if (unwrap(error) instanceof BalanceException failure && failure.action() == BalanceException.Action.ACCESS_OPENAI_ADMIN) {
                    showAdminConfiguration(); setKeyStateText("暂时无法确认保存状态。解锁或允许访问系统钥匙串后，点击查询重试。");
                }
            } else if (state != null && state != AdminKeyState.UNKNOWN) {
                showAdminConfiguration(); setAdminKeyState(state);
            } else if (adminSettings.isVisible()) setAdminKeyState(AdminKeyState.UNKNOWN);
        }));
    }

    private void setAdminKeyState(AdminKeyState state) {
        applyAdmin.setText(state == AdminKeyState.SAVED ? "更换并保存" : "保存并查询");
        adminInput.setPromptText(state == AdminKeyState.SAVED
                ? "已保存，无需重填；仅更换时粘贴新 key" : "粘贴 OpenAI Admin API key");
        setKeyStateText(switch (state) {
            case SAVED -> "管理密钥已保存到 macOS 钥匙串，查询时自动读取。输入框仅用于更换，不显示已保存内容。";
            case SESSION -> balances.persistsOpenAiAdminKey()
                    ? "当前运行已配置管理密钥。填写并保存后可在重启后使用。"
                    : "当前运行已配置管理密钥，此系统暂不支持退出后保留。";
            case MISSING -> "尚未配置管理密钥，请在本地填写后保存。";
            case UNKNOWN -> "已保存的密钥不会回填输入框，可直接点击查询。";
        });
    }

    private void setKeyStateText(String text) { adminState.setText(text); adminState.setVisible(!text.isBlank()); }

    private void resetReport() {
        report = null; read.setDisable(true); title.setText("当前对话 API"); updated.setText("尚未查询");
        fundsForm.setVisible(false); balanceInput.clear();
    }

    private void working(boolean value) {
        refresh.setDisable(value); syncFunds.setDisable(value); clearFunds.setDisable(value); balanceInput.setDisable(value);
    }
    private void editingCredential(boolean value) {
        clearAdmin.setDisable(value); adminSettings.setDisable(value); adminInput.setDisable(value);
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
