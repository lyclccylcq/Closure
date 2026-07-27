package app.service;

import app.chat.model.AssistantReply;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSpeechControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void speaksSanitizedReply() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakText("**博士**，资料在这里：https://example.com");

        assertEquals(List.of("博士，资料在这里。"), speechService.spokenTexts);
        assertEquals(1, speechService.stopCount);
    }

    @Test
    void speaksAssistantReplySpeechTextOnlyOnce() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());
        AssistantReply reply = new AssistantReply(
                "第一句。第二句？第三句！",
                "第一句。第二句？第三句！",
                SpeechEmotion.NEUTRAL
        );

        controller.speakAssistantReply(reply);

        assertEquals(List.of("第一句。第二句？第三句！"), speechService.spokenTexts);
        assertEquals(1, speechService.spokenProfiles.size());
    }

    @Test
    void usesDominantEmotionProfile() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakAssistantReply(new AssistantReply("真的？", "真的？", SpeechEmotion.QUESTION));

        assertEquals("question", speechService.spokenProfiles.getFirst().getId());
    }

    @Test
    void skipsEmptySanitizedReply() throws Exception {
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
    void disabledAutoSpeakDoesNotCallTtsAndStopsCurrentSpeechWhenTurnedOff() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.setAutoSpeakEnabled(false);
        controller.speakAssistantReply("博士，测试。");

        assertTrue(speechService.spokenTexts.isEmpty());
        assertEquals(1, speechService.stopCount);
    }

    @Test
    void newReplyStopsPreviousSpeechWithoutBlockingNewReply() throws Exception {
        FakeSpeechService speechService = new FakeSpeechService();
        ChatSpeechController controller = controller(speechService, true, new ArrayList<>());

        controller.speakAssistantReply("第一条。");
        controller.speakAssistantReply("第二条。");

        assertEquals(List.of("第一条。", "第二条。"), speechService.spokenTexts);
        assertEquals(2, speechService.stopCount);
    }

    @Test
    void repeatedSameFailureWarningsAreCollapsed() throws Exception {
        List<String> warnings = new ArrayList<>();
        FakeSpeechService speechService = new FakeSpeechService();
        speechService.failureMessage = "语音合成失败，HTTP 400: bad request";
        ChatSpeechController controller = controller(speechService, true, warnings);

        controller.speakAssistantReply("第一条。");
        controller.speakAssistantReply("第二条。");

        assertEquals(1, warnings.size());
    }

    @Test
    void ttsFailureDoesNotEscapeChatFlow() throws Exception {
        List<String> warnings = new ArrayList<>();
        FakeSpeechService speechService = new FakeSpeechService();
        speechService.failureMessage = "Connection refused";
        ChatSpeechController controller = controller(speechService, true, warnings);

        assertDoesNotThrow(() -> controller.speakAssistantReply(new AssistantReply(
                "文字已经显示。",
                "文字已经显示。",
                SpeechEmotion.NEUTRAL
        )));
    }

    private ChatSpeechController controller(
            FakeSpeechService speechService,
            boolean autoSpeakEnabled,
            List<String> warnings
    ) throws IOException {
        return new ChatSpeechController(
                speechService,
                registry(),
                autoSpeakEnabled,
                warnings::add
        );
    }

    private VoiceProfileRegistry registry() throws IOException {
        VoiceProfile neutral = profile("neutral", "neutral.wav");
        VoiceProfile question = profile("question", "question.wav");
        return new VoiceProfileRegistry(
                Map.of(
                        SpeechEmotion.NEUTRAL, neutral,
                        SpeechEmotion.QUESTION, question
                ),
                message -> {
                }
        );
    }

    private VoiceProfile profile(String id, String fileName) throws IOException {
        Path audioPath = tempDir.resolve(fileName);
        Files.write(audioPath, "RIFF".getBytes(StandardCharsets.US_ASCII));
        return new VoiceProfile(id, audioPath, "参考文本。", "zh");
    }

    private static final class FakeSpeechService implements AssistantSpeechService {
        private final List<String> spokenTexts = new ArrayList<>();
        private final List<VoiceProfile> spokenProfiles = new ArrayList<>();
        private final List<CompletableFuture<Void>> futures = new ArrayList<>();
        private int stopCount;
        private String failureMessage;

        @Override
        public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
            spokenTexts.add(text);
            spokenProfiles.add(profile);
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
