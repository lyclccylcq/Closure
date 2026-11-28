package app.ui.chat;

import app.service.AssistantSpeechService;
import app.service.ChatSpeechController;
import app.ui.PetWindow;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import app.weather.WeatherGreetingService;
import app.weather.WeatherSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeGreetingTest", matches="true")
class StartupGreetingWindowTest {
    @Test void realPetSpeaksOneTimedGreetingAndHonorsMuteWarmupFailureAndDisposal() throws Exception {
        List<String> spoken = new CopyOnWriteArrayList<>();
        var warmup = new CompletableFuture<Void>();
        var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                new VoiceProfile("synthetic", Path.of("unused.wav"), "示例", "zh")), message -> {}, true);
        ChatSpeechController speech = new ChatSpeechController(new AssistantSpeechService() {
            public CompletableFuture<Void> warmUp(VoiceProfile profile) { return warmup; }
            public CompletableFuture<Void> speak(String text, VoiceProfile profile) { spoken.add(text); return CompletableFuture.completedFuture(null); }
            public void stop() { }
        }, profiles, true, message -> {});
        var weather = new CompletableFuture<WeatherSnapshot>();
        var greeting = new WeatherGreetingService(() -> weather,
                Clock.fixed(Instant.parse("2026-11-28T00:30:00Z"), ZoneId.of("Asia/Shanghai"))).startupGreeting();
        PetWindow pet = swing(() -> {
            PetWindow owner = PetWindow.startupSmokeWindow();
            set(owner, "speechController", speech); set(owner, "startupWeatherGreetingFuture", greeting);
            owner.setVisible(true); begin(owner); return owner;
        });
        try {
            assertTrue(spoken.isEmpty());
            weather.complete(new WeatherSnapshot("Canberra", 18, 18, 3, 5, 23, 12, 3, 5, 0));
            assertTrue(spoken.isEmpty());
            warmup.completeExceptionally(new IllegalStateException("Synthetic readiness failure"));
            await(() -> spoken.size() == 1);
            assertTrue(spoken.getFirst().contains("早上好")); assertTrue(spoken.getFirst().contains("12到23度"));
            assertFalse(spoken.getFirst().contains("Canberra"));
            swing(() -> { begin(pet); return null; }); swing(() -> null);
            assertEquals(1, spoken.size());
            assertNull(field(pet, "workbenchWindow"));
        } finally { swing(() -> { pet.dispose(); return null; }); speech.close(); }

        var late = new CompletableFuture<String>();
        var muted = new ChatSpeechController(new AssistantSpeechService() {
            public CompletableFuture<Void> speak(String text, VoiceProfile profile) { spoken.add(text); return CompletableFuture.completedFuture(null); }
            public void stop() { }
        }, profiles, false, message -> {});
        PetWindow quiet = swing(() -> {
            PetWindow owner = PetWindow.startupSmokeWindow();
            set(owner, "speechController", muted); set(owner, "startupWeatherGreetingFuture", CompletableFuture.completedFuture("晚上好，博士！天气还没查到，先不乱报。早点休息呀。"));
            owner.setVisible(true); begin(owner); return owner;
        });
        swing(() -> null); assertEquals(1, spoken.size());
        swing(() -> { set(quiet, "startupWeatherGreetingFuture", late); muted.setAutoSpeakEnabled(true); begin(quiet); quiet.dispose(); return null; });
        late.complete("关闭后不得朗读。"); swing(() -> null); assertEquals(1, spoken.size()); muted.close();
        System.out.println("CLOSURE_STARTUP_GREETING_NATIVE_PASS oneGreeting=true time=true localWeather=true noPlace=true warmupFailure=true mute=true disposed=true petOnly=true");
    }
    private static void begin(PetWindow pet) throws Exception { var method = PetWindow.class.getDeclaredMethod("showStartupWeatherGreetingAfterSpeechReady"); method.setAccessible(true); method.invoke(pet); }
    private static void set(Object owner, String name, Object value) throws Exception { var f = PetWindow.class.getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    private static Object field(Object owner, String name) throws Exception { var f = PetWindow.class.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }
}
