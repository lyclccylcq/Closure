package app.ui.chat;

import app.billing.OpenAiFundsFixture;
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
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeFundsTest", matches="true")
class FundsSyncWindowTest {
    @TempDir Path directory;
    private WorkbenchWindow workbench;
    private final List<String> spoken = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<CompletableFuture<Void>> audio = new LinkedBlockingQueue<>();

    @Test void actualCostsAndPersistentCalibrationDriveOneDecimalSpeechAndLowFundsCharacterLine() throws Exception {
        try (var fixture = new OpenAiFundsFixture(directory.resolve("funds.json"))) {
            var character = new AtomicReference<CharacterController>();
            var chat = new ChatWindowLifecycleTest.Client();
            var teasing = new VoiceProfile("teasing", Path.of("unused.wav"), "示例", "zh");
            var teasingUsed = new AtomicBoolean();
            var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                    new VoiceProfile("neutral", Path.of("unused.wav"), "示例", "zh"), SpeechEmotion.TEASING, teasing), message -> {}, true);
            var speech = new ChatSpeechController(new AssistantSpeechService() {
                public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
                    spoken.add(text); if (profile == teasing) teasingUsed.set(true);
                    var future = new CompletableFuture<Void>(); audio.add(future); return future;
                }
                public void stop() { }
            }, profiles, true, message -> {});
            speech.setSpeechLifecycleListener(new SpeechLifecycleListener() {
                public void onSpeechStarted() { character.get().setTalking(true); }
                public void onSpeechFinished() { character.get().setTalking(false); }
            });
            PetWindow pet = swing(() -> {
                PetWindow owner = PetWindow.startupSmokeWindow(() -> {
                    workbench = new WorkbenchWindow(() -> new ChatService(chat), speech, character.get(), fixture.service()); return workbench;
                });
                var field = PetWindow.class.getDeclaredField("characterController"); field.setAccessible(true);
                character.set((CharacterController) field.get(owner)); owner.setVisible(true); owner.openWorkbenchWindow(); return owner;
            });
            try {
                workbench.whenReady().get(10, TimeUnit.SECONDS);
                fx(() -> { ((ToggleButton) view().lookup("#workbench-toggle-balance")).fire(); return null; });
                var initial = audio.poll(3, TimeUnit.SECONDS); assertNotNull(initial);
                assertTrue(spoken.getLast().contains("1.2美元")); assertFalse(spoken.getLast().contains("采购费"));
                assertFalse(spoken.getLast().contains("tokens")); initial.complete(null);
                int before = fixture.requests().size();
                fx(() -> { input().clear(); button("workbench-funds-sync").fire(); return null; });
                assertEquals(before, fixture.requests().size());
                assertTrue(fx(() -> ((Label) view().lookup("#workbench-balance-updated")).getText().contains("填写")));
                fx(() -> { input().setText("6.0"); button("workbench-funds-sync").fire(); return null; });
                var synced = audio.poll(3, TimeUnit.SECONDS); assertNotNull(synced);
                assertTrue(fx(() -> result().getText().contains("6.0 USD") && input().getText().isEmpty()));
                assertTrue(Files.isRegularFile(directory.resolve("funds.json"))); synced.complete(null);
                fixture.setCost("3.234567");
                fx(() -> { button("workbench-balance-refresh").fire(); return null; });
                var low = audio.poll(3, TimeUnit.SECONDS); assertNotNull(low);
                assertTrue(spoken.getLast().contains("3.2美元")); assertTrue(spoken.getLast().contains("采购费"));
                assertTrue(spoken.getLast().contains("估算")); assertTrue(teasingUsed.get());
                assertTrue(fx(() -> result().getText().contains("4.0 USD") && !result().getText().contains("tokens")));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (character.get().state() != CharacterState.TALKING && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(CharacterState.TALKING, character.get().state());
                fx(() -> {
                    view().applyCss(); view().layout(); capture();
                    button("workbench-balance-read").fire(); return null;
                });
                var repeat = audio.poll(3, TimeUnit.SECONDS); assertNotNull(repeat);
                assertEquals(spoken.get(spoken.size() - 2), spoken.getLast()); repeat.complete(null); low.complete(null);
                fx(() -> { button("workbench-funds-clear").fire(); return null; });
                assertFalse(Files.exists(directory.resolve("funds.json")));
                assertTrue(fx(() -> result().getText().contains("余额待同步")));
                assertTrue(chat.calls.isEmpty());
                System.out.println("CLOSURE_FUNDS_SYNC_NATIVE_PASS actualHttp=true dailySummary=true oneDecimal=true persistentSync=true lowFunds=true teasingSpeech=true replay=true clear=true noModelCalls=true");
            } finally { swing(() -> { pet.dispose(); return null; }); speech.close(); }
        }
    }
    private WorkbenchView view() { return (WorkbenchView) ((JFXPanel) workbench.getContentPane()).getScene().getRoot(); }
    private Button button(String id) { return (Button) view().lookup("#" + id); }
    private TextField input() { return (TextField) view().lookup("#workbench-funds-input"); }
    private Label result() { return (Label) view().lookup("#workbench-balance-result"); }
    private void capture() throws Exception {
        String folder = System.getProperty("closure.fundsEvidence"); if (folder == null) return;
        Files.createDirectories(Path.of(folder));
        ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null), "png", Path.of(folder,"funds-sync.png").toFile());
    }
}
