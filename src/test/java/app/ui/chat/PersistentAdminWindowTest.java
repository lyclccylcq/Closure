package app.ui.chat;

import app.billing.OpenAiFundsFixture;
import app.billing.BalanceService;
import app.billing.BalanceReport;
import app.character.CharacterController;
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
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativePersistentAdminTest", matches="true")
class PersistentAdminWindowTest {
    @TempDir Path directory;
    private WorkbenchWindow workbench;
    private final AtomicReference<CharacterController> character = new AtomicReference<>();
    private final LinkedBlockingQueue<CompletableFuture<Void>> audio = new LinkedBlockingQueue<>();

    @Test void actualWorkbenchSavesThenReopensWithoutReentryAndClearRemovesTheCredential() throws Exception {
        try (var fixture = new OpenAiFundsFixture(directory.resolve("funds.json"))) {
            var chat = new ChatWindowLifecycleTest.Client();
            var speech = new ChatSpeechController(new AssistantSpeechService() {
                public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
                    assertFalse(text.contains("synthetic-persisted-admin"));
                    var future = new CompletableFuture<Void>(); audio.add(future); return future;
                }
                public void stop() { }
            }, new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                    new VoiceProfile("fixture", Path.of("unused.wav"), "示例", "zh")), message -> {}, true), true, message -> {});
            speech.setSpeechLifecycleListener(new SpeechLifecycleListener() {
                public void onSpeechStarted() { character.get().setTalking(true); }
                public void onSpeechFinished() { character.get().setTalking(false); }
            });
            var saveGate = new CompletableFuture<Void>();
            PetWindow firstPet = open(fixture, speech, chat, saveGate);
            try {
                workbench.whenReady().get(10, TimeUnit.SECONDS); query();
                await(() -> fx(() -> result().getText().contains("Admin API key")));
                fx(() -> {
                    assertTrue(((Label) view().lookup("#workbench-admin-storage")).getText().contains("钥匙串"));
                    input().setText("synthetic-persisted-admin"); button("workbench-admin-apply").fire();
                    assertTrue(input().getText().isEmpty()); input().undo(); assertTrue(input().getText().isEmpty()); return null;
                });
                speech.speakFullText("后来的前台对话。");
                var foreground = audio.poll(3, TimeUnit.SECONDS); assertNotNull(foreground);
                saveGate.complete(null);
                await(() -> fx(() -> result().getText().contains("1.2 USD")));
                assertNull(audio.poll(150, TimeUnit.MILLISECONDS)); assertFalse(foreground.isCancelled()); foreground.complete(null);
                fx(() -> { button("workbench-balance-read").fire(); return null; });
                var first = audio.poll(3, TimeUnit.SECONDS); assertNotNull(first); first.complete(null);
            } finally { swing(() -> { firstPet.dispose(); return null; }); }

            PetWindow restoredPet = open(fixture, speech, chat, null);
            try {
                workbench.whenReady().get(10, TimeUnit.SECONDS); query();
                var restored = audio.poll(3, TimeUnit.SECONDS); assertNotNull(restored); restored.complete(null);
                fx(() -> {
                    assertTrue(result().getText().contains("1.2 USD")); assertTrue(input().getText().isEmpty());
                    assertFalse(input().getParent().isVisible());
                    button("workbench-admin-settings").fire(); assertTrue(input().getText().isEmpty());
                    view().applyCss(); view().layout(); capture();
                    button("workbench-admin-clear").fire(); return null;
                });
                await(() -> fx(() -> result().getText().contains("管理密钥已清除")));
            } finally { swing(() -> { restoredPet.dispose(); return null; }); }

            PetWindow clearedPet = open(fixture, speech, chat, null);
            try {
                workbench.whenReady().get(10, TimeUnit.SECONDS); query();
                await(() -> fx(() -> result().getText().contains("Admin API key")));
                assertTrue(chat.calls.isEmpty());
                System.out.println("CLOSURE_PERSISTENT_ADMIN_NATIVE_PASS masked=true saved=true reopened=true noPrefill=true noReentry=true cleared=true missingAfterReopen=true newerSpeechWins=true noModelCalls=true");
            } finally { swing(() -> { clearedPet.dispose(); return null; }); speech.close(); }
        }
    }
    private PetWindow open(OpenAiFundsFixture fixture, ChatSpeechController speech, ChatWindowLifecycleTest.Client chat, CompletableFuture<Void> gate) throws Exception {
        return swing(() -> {
            PetWindow owner = PetWindow.startupSmokeWindow(() -> {
                BalanceService actual = fixture.persistentService();
                BalanceService balances = gate == null ? actual : new BalanceService() {
                    public CompletableFuture<BalanceReport> query() { return actual.query(); }
                    public boolean canConfigureOpenAiAdminKey() { return true; }
                    public boolean persistsOpenAiAdminKey() { return true; }
                    public CompletableFuture<Void> configureOpenAiAdminKeyAsync(String key) {
                        return actual.configureOpenAiAdminKeyAsync(key).thenCompose(ignored -> gate);
                    }
                    public void close() { actual.close(); }
                };
                workbench = new WorkbenchWindow(() -> new ChatService(chat), speech, character.get(), balances); return workbench;
            });
            var field = PetWindow.class.getDeclaredField("characterController"); field.setAccessible(true);
            character.set((CharacterController) field.get(owner)); owner.setVisible(true); owner.openWorkbenchWindow(); return owner;
        });
    }
    private void query() throws Exception { fx(() -> { ((ToggleButton) view().lookup("#workbench-toggle-balance")).fire(); return null; }); }
    private WorkbenchView view() { return (WorkbenchView) ((JFXPanel) workbench.getContentPane()).getScene().getRoot(); }
    private Button button(String id) { return (Button) view().lookup("#" + id); }
    private PasswordField input() { return (PasswordField) view().lookup("#workbench-admin-key"); }
    private Label result() { return (Label) view().lookup("#workbench-balance-result"); }
    private void capture() throws Exception {
        String folder = System.getProperty("closure.persistentAdminEvidence"); if (folder == null) return;
        Files.createDirectories(Path.of(folder));
        ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null), "png", Path.of(folder,"persistent-admin.png").toFile());
    }
    private static void await(Checked condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.test() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.test());
    }
    private interface Checked { boolean test() throws Exception; }
}
