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
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeBalanceTest", matches="true")
class WorkbenchBalanceWindowTest {
    private WorkbenchWindow workbench;
    private final BlockingQueue<CompletableFuture<BalanceReport>> queries = new LinkedBlockingQueue<>();
    private final BlockingQueue<CompletableFuture<Void>> audio = new LinkedBlockingQueue<>();
    private final List<String> spoken = new CopyOnWriteArrayList<>();

    @Test void explicitBalanceQueriesReportThroughTheSharedLivePetWithoutModelCallsOrStaleSpeech() throws Exception {
        AtomicReference<CharacterController> character = new AtomicReference<>();
        var client = new ChatWindowLifecycleTest.Client();
        AtomicInteger queryCount = new AtomicInteger();
        BalanceService balances = () -> { queryCount.incrementAndGet(); var pending = new CompletableFuture<BalanceReport>(); queries.add(pending); return pending; };
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
                workbench = new WorkbenchWindow(() -> new ChatService(client), speech, character.get(), balances); return workbench;
            });
            character.set((CharacterController) field(owner, "characterController"));
            owner.setVisible(true); owner.openWorkbenchWindow(); return owner;
        });
        try {
            workbench.whenReady().get(10, TimeUnit.SECONDS);
            await(() -> fx(() -> view().lookup("#workbench-mascot") != null));
            assertEquals(0, queryCount.get());
            fx(() -> { toggle().fire(); button("workbench-balance-refresh").fire(); assertTrue(button("workbench-balance-refresh").isDisabled()); return null; });
            var first = queries.poll(3, TimeUnit.SECONDS); assertNotNull(first); assertEquals(1, queryCount.get());
            assertTrue(fx(() -> view().lookup("#workbench-editor") != null));
            await(() -> character.get().state() == CharacterState.THINKING);
            first.complete(report());
            var firstAudio = audio.poll(3, TimeUnit.SECONDS); assertNotNull(firstAudio);
            await(() -> character.get().state() == CharacterState.TALKING);
            fx(() -> {
                assertTrue(result().getText().contains("12.3 USD"));
                assertTrue(((Label) view().lookup("#workbench-balance-updated")).getText().contains("11-28"));
                view().applyCss(); view().layout(); capture();
                button("workbench-balance-read").fire(); return null;
            });
            var repeated = audio.poll(3, TimeUnit.SECONDS); assertNotNull(repeated);
            assertEquals(2, spoken.size()); assertTrue(spoken.getFirst().contains("美元"));
            repeated.complete(null); firstAudio.complete(null);

            fx(() -> { button("workbench-balance-refresh").fire(); return null; });
            var delayed = queries.poll(3, TimeUnit.SECONDS); assertNotNull(delayed);
            speech.speakFullText("后来的前台对话。");
            var foreground = audio.poll(3, TimeUnit.SECONDS); assertNotNull(foreground);
            int before = spoken.size(); delayed.complete(report());
            fx(() -> null); fx(() -> null); assertEquals(before, spoken.size());
            fx(() -> { ((Button) view().lookup("#workbench-new-session")).fire(); return null; });
            assertFalse(foreground.isCancelled(), "Closing the balance tool must not stop another speech owner"); foreground.complete(null);

            fx(() -> { button("workbench-balance-refresh").fire(); return null; });
            var failing = queries.poll(3, TimeUnit.SECONDS); assertNotNull(failing);
            failing.completeExceptionally(new BalanceException("平台没有允许此 token 查询余额。"));
            await(() -> fx(() -> result().getText().contains("没有允许")));
            assertEquals(before, spoken.size());
            fx(() -> { button("workbench-balance-refresh").fire(); return null; });
            var hidden = queries.poll(3, TimeUnit.SECONDS); assertNotNull(hidden);
            swing(() -> { workbench.setVisible(false); return null; });
            await(hidden::isCancelled); assertFalse(hidden.complete(report()));
            await(pet::isVisible); assertEquals(before, spoken.size());
            assertTrue(client.calls.isEmpty(), "Balance facts must never be sent to the LLM");
            System.out.println("CLOSURE_BALANCE_NATIVE_PASS realPet=true loading=true precision=true noModelCalls=true sharedSpeech=true replay=true newerSpeechWins=true failure=true retry=true hiddenCancellation=true");
        } finally { swing(() -> { pet.dispose(); return null; }); speech.close(); }
    }
    private WorkbenchView view() { return (WorkbenchView) ((JFXPanel) workbench.getContentPane()).getScene().getRoot(); }
    private Button button(String id) { return (Button) view().lookup("#" + id); }
    private ToggleButton toggle() { return (ToggleButton) view().lookup("#workbench-toggle-balance"); }
    private Label result() { return (Label) view().lookup("#workbench-balance-result"); }
    private static BalanceReport report() { return new BalanceReport("本地替身平台", List.of(new BalanceReport.Line("账户", new BigDecimal("12.345678"), "USD", false)), "", Instant.parse("2026-11-28T03:00:00Z")); }
    private void capture() throws Exception {
        String folder = System.getProperty("closure.balanceEvidence"); if (folder == null) return;
        Files.createDirectories(Path.of(folder));
        ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null), "png", Path.of(folder, "balance-report.png").toFile());
    }
    private static Object field(Object owner, String name) throws Exception { var f = PetWindow.class.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private static void await(Checked condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.test() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.test());
    }
    private interface Checked { boolean test() throws Exception; }
}
