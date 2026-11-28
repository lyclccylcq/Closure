package app.ui.chat;

import app.billing.BalanceException;
import app.billing.BalanceReport;
import app.billing.BalanceService;
import app.character.CharacterController;
import app.character.CharacterState;
import app.service.AssistantSpeechService;
import app.service.ChatService;
import app.service.ChatSpeechController;
import app.service.SpeechLifecycleListener;
import app.ui.PetWindow;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeAdminTest", matches="true")
class OpenAiAdminWindowTest {
    private WorkbenchWindow workbench;
    private final BlockingQueue<CompletableFuture<BalanceReport>> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<CompletableFuture<Void>> audio = new LinkedBlockingQueue<>();
    private final List<String> spoken = new CopyOnWriteArrayList<>();
    private volatile String configured;

    @Test void maskedLocalAdminConfigurationReportsCostsAndKeepsOrdinaryChatWorking() throws Exception {
        AtomicReference<CharacterController> character = new AtomicReference<>();
        var chat = new ChatWindowLifecycleTest.Client();
        BalanceService balances = new BalanceService() {
            public boolean canConfigureOpenAiAdminKey() { return true; }
            public void configureOpenAiAdminKey(String key) { configured = key; }
            public void clearOpenAiAdminKey() { configured = null; }
            public void close() { configured = null; }
            public CompletableFuture<BalanceReport> query() {
                if (configured == null) return CompletableFuture.failedFuture(new BalanceException("请先配置 OpenAI Admin API key。", BalanceException.Action.CONFIGURE_OPENAI_ADMIN));
                var pending = new CompletableFuture<BalanceReport>(); requests.add(pending); return pending;
            }
        };
        var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                new VoiceProfile("synthetic", Path.of("unused.wav"), "示例", "zh")), message -> {}, true);
        var speech = new ChatSpeechController(new AssistantSpeechService() {
            public CompletableFuture<Void> speak(String text, VoiceProfile profile) { spoken.add(text); var pending = new CompletableFuture<Void>(); audio.add(pending); return pending; }
            public void stop() { }
        }, profiles, true, message -> {});
        speech.setSpeechLifecycleListener(new SpeechLifecycleListener() {
            public void onSpeechStarted() { character.get().setTalking(true); }
            public void onSpeechFinished() { character.get().setTalking(false); }
        });
        PetWindow pet = swing(() -> {
            PetWindow owner = PetWindow.startupSmokeWindow(() -> {
                workbench = new WorkbenchWindow(() -> new ChatService(chat), speech, character.get(), balances); return workbench;
            });
            var field = PetWindow.class.getDeclaredField("characterController"); field.setAccessible(true);
            character.set((CharacterController) field.get(owner)); owner.setVisible(true); owner.openWorkbenchWindow(); return owner;
        });
        try {
            workbench.whenReady().get(10, TimeUnit.SECONDS);
            fx(() -> { ((ToggleButton) view().lookup("#workbench-toggle-balance")).fire(); return null; });
            await(() -> fx(() -> input().isVisible() && result().getText().contains("Admin API key")));
            fx(() -> {
                view().applyCss(); view().layout(); capture("admin-configuration");
                input().setText("synthetic-admin-local-only"); button("workbench-admin-apply").fire();
                assertTrue(input().getText().isEmpty()); input().undo(); assertTrue(input().getText().isEmpty());
                assertTrue(button("workbench-balance-refresh").isDisabled()); return null;
            });
            var pending = requests.poll(3, TimeUnit.SECONDS); assertNotNull(pending);
            pending.complete(report());
            var first = audio.poll(3, TimeUnit.SECONDS); assertNotNull(first);
            await(() -> character.get().state() == CharacterState.TALKING);
            fx(() -> {
                assertTrue(result().getText().contains("今日消耗")); assertTrue(result().getText().contains("0.1 USD"));
                assertFalse(result().getText().contains("tokens")); assertFalse(result().getText().contains("本月"));
                assertFalse(result().getText().contains("剩余")); assertTrue(view().lookup("#workbench-openai-billing").isVisible());
                for (var node : view().lookupAll(".label")) assertFalse(((Label) node).getText().contains("synthetic-admin-local-only"));
                view().applyCss(); view().layout(); capture("admin-report");
                button("workbench-balance-read").fire(); return null;
            });
            var repeat = audio.poll(3, TimeUnit.SECONDS); assertNotNull(repeat);
            assertEquals(2, spoken.size()); assertFalse(spoken.getFirst().contains("剩余"));
            assertTrue(spoken.getFirst().contains("0.1美元")); assertFalse(spoken.getFirst().contains("tokens"));
            assertEquals(spoken.getFirst(), spoken.getLast()); repeat.complete(null); first.complete(null);
            assertTrue(chat.calls.isEmpty(), "Admin results must not enter the language model");
            fx(() -> {
                button("workbench-admin-settings").fire(); assertTrue(input().isVisible());
                button("workbench-admin-clear").fire();
                assertTrue(input().getText().isEmpty()); assertTrue(button("workbench-balance-read").isDisabled());
                return null;
            });
            await(() -> configured == null && fx(() -> !button("workbench-admin-clear").isDisabled()));
            fx(() -> {
                button("workbench-admin-settings").fire();
                ((TextArea) view().lookup("#workbench-editor")).setText("管理密钥清除后正常聊天。");
                button("workbench-send").fire(); return null;
            });
            var ordinary = chat.take(); ordinary.finish("普通对话仍然正常。");
            var chatAudio = audio.poll(3, TimeUnit.SECONDS); assertNotNull(chatAudio); chatAudio.complete(null);
            fx(() -> {
                button("workbench-admin-settings").fire(); input().setText("synthetic-second-admin");
                button("workbench-admin-apply").fire(); return null;
            });
            var hidden = requests.poll(3, TimeUnit.SECONDS); assertNotNull(hidden);
            swing(() -> { workbench.setVisible(false); return null; }); await(hidden::isCancelled);
            assertFalse(hidden.complete(report()));
            System.out.println("CLOSURE_OPENAI_ADMIN_NATIVE_PASS localPassword=true clearedInput=true noUndoSecret=true costsNotBalance=true sharedPetSpeech=true replay=true noModelLeak=true clear=true ordinaryChat=true hiddenCancellation=true");
        } finally { swing(() -> { pet.dispose(); return null; }); fx(() -> null); speech.close(); assertNull(configured); }
    }

    private WorkbenchView view() { return (WorkbenchView) ((JFXPanel) workbench.getContentPane()).getScene().getRoot(); }
    private Button button(String id) { return (Button) view().lookup("#" + id); }
    private PasswordField input() { return (PasswordField) view().lookup("#workbench-admin-key"); }
    private Label result() { return (Label) view().lookup("#workbench-balance-result"); }
    private static BalanceReport report() {
        return new BalanceReport("OpenAI · 组织费用与用量", List.of(
                new BalanceReport.Line("今日费用（UTC）", new BigDecimal("0.12"), "USD", false, BalanceReport.Kind.COST),
                new BalanceReport.Line("本月费用（UTC）", new BigDecimal("1.23"), "USD", false, BalanceReport.Kind.COST),
                new BalanceReport.Line("本月输入", new BigDecimal("1200"), "tokens", false, BalanceReport.Kind.USAGE),
                new BalanceReport.Line("本月输出", new BigDecimal("300"), "tokens", false, BalanceReport.Kind.USAGE)),
                "按管理密钥的组织汇总，日期为 UTC。费用不等于充值余额。", Instant.parse("2026-11-28T03:00:00Z"));
    }
    private void capture(String name) throws Exception {
        String folder = System.getProperty("closure.adminEvidence"); if (folder == null) return;
        Files.createDirectories(Path.of(folder));
        ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null), "png", Path.of(folder, name + ".png").toFile());
    }
    private static void await(Checked condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.test() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.test());
    }
    private interface Checked { boolean test() throws Exception; }
}
