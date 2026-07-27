package app.service;

import app.voice.model.SpeechSegment;
import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSpeechControllerTest {
    @Test
    void speaksSanitizedReply() {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakText("**博士**，资料在这里：https://example.com");

        assertEquals(List.of("博士，资料在这里。"), speechService.spokenTexts);
        assertEquals(1, speechService.stopCount);
    }

    @Test
    void skipsEmptySanitizedReply() {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakAssistantReply("""
                ```java
                System.out.println("skip");
                ```
                https://example.com
                """);

        assertTrue(speechService.spokenTexts.isEmpty());
        assertEquals(0, speechService.stopCount);
    }

    @Test
    void disabledAutoSpeakDoesNotCallTtsAndStopsCurrentSpeechWhenTurnedOff() {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.setAutoSpeakEnabled(false);
        controller.speakAssistantReply("博士，测试。");

        assertTrue(speechService.spokenTexts.isEmpty());
        assertEquals(1, speechService.stopCount);
    }

    @Test
    void newReplyCancelsPreviousSpeechFuture() {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakAssistantReply("第一条。");
        CompletableFuture<Void> firstFuture = speechService.futures.getFirst();
        controller.speakAssistantReply("第二条。");

        assertTrue(firstFuture.isCancelled());
        assertEquals(List.of("第一条。", "第二条。"), speechService.spokenTexts);
        assertEquals(2, speechService.stopCount);
    }

    @Test
    void repeatedSameFailureWarningsAreCollapsed() {
        List<String> warnings = new ArrayList<>();
        FakeSpeechService speechService = new FakeSpeechService();
        speechService.failureMessage = "语音合成失败，HTTP 400: bad request";
        ChatSpeechController controller = controller(speechService, true, warnings);

        controller.speakAssistantReply("第一条。");
        controller.speakAssistantReply("第二条。");

        assertEquals(1, warnings.size());
    }

    private ChatSpeechController controller(
            FakeSpeechService speechService,
            boolean autoSpeakEnabled,
            List<String> warnings
    ) {
        return new ChatSpeechController(
                speechService,
                new VoiceProfile("neutral", Path.of("/tmp/ref.wav"), "参考文本。", "zh"),
                autoSpeakEnabled,
                warnings::add
        );
    }

    private static final class FakeSpeechService implements AssistantSpeechService {
        private final List<String> spokenTexts = new ArrayList<>();
        private final List<CompletableFuture<Void>> futures = new ArrayList<>();
        private int stopCount;
        private String failureMessage;

        @Override
        public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
            spokenTexts.add(text);
            return nextFuture();
        }

        @Override
        public CompletableFuture<Void> speakSegments(List<SpeechSegment> segments) {
            for (SpeechSegment segment : segments) {
                spokenTexts.add(segment.getText());
            }
            return nextFuture();
        }

        private CompletableFuture<Void> nextFuture() {
            if (failureMessage != null) {
                return CompletableFuture.failedFuture(new RuntimeException(failureMessage));
            }

            CompletableFuture<Void> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }
}
