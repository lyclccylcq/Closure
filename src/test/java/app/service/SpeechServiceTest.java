package app.service;

import app.voice.VoiceSynthesisClient;
import app.voice.model.FileTtsAudioResult;
import app.voice.model.TtsAudioResult;
import app.voice.model.TtsRequest;
import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void oneReplyWithThreeSentencesCallsSynthesizeOnce() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer);

        CompletableFuture<Void> result = speechService.speak("第一句。第二句？第三句！", profile("neutral", "neutral.wav"));

        assertEquals(1, ttsClient.initializeCount);
        assertEquals(1, ttsClient.requests.size());
        Path audio = outputAudio("single.wav");
        ttsClient.synthesisFutures.getFirst().complete(new FileTtsAudioResult(audio));
        assertEquals(List.of(audio), audioPlayer.playedFiles);
        audioPlayer.playbackFutures.getFirst().complete(null);

        assertTrue(result.isDone());
    }

    @Test
    void modelInitializationOnlyRunsOnceAcrossWarmupAndSpeak() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer);
        VoiceProfile neutral = profile("neutral", "neutral.wav");

        CompletableFuture<Void> warmUp = speechService.warmUp(neutral);
        Path warmupAudio = outputAudio("warmup.wav");
        ttsClient.synthesisFutures.getFirst().complete(new FileTtsAudioResult(warmupAudio));
        warmUp.join();

        speechService.speak("博士，测试。", neutral);

        assertEquals(1, ttsClient.checkCount);
        assertEquals(1, ttsClient.initializeCount);
        assertEquals(2, ttsClient.requests.size());
        assertTrue(speechService.isReady());
        assertFalse(Files.exists(warmupAudio));
    }

    @Test
    void warmupFailureDoesNotPreventLaterSpeech() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer);
        VoiceProfile missingProfile = new VoiceProfile(
                "missing",
                tempDir.resolve("missing.wav"),
                "参考文本。",
                "zh"
        );
        VoiceProfile neutral = profile("neutral", "neutral.wav");

        CompletableFuture<Void> warmUp = speechService.warmUp(missingProfile);
        assertTrue(warmUp.isCompletedExceptionally());

        speechService.speak("博士，后续朗读。", neutral);

        assertEquals(1, ttsClient.requests.size());
        assertEquals("博士，后续朗读。", ttsClient.requests.getFirst().getText());
    }

    @Test
    void failedInitializationIsRetriedForLaterSpeech() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        ttsClient.initializationFailuresRemaining = 1;
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer);
        VoiceProfile neutral = profile("neutral", "neutral.wav");

        CompletableFuture<Void> firstSpeech = speechService.speak("第一次。", neutral);
        assertTrue(firstSpeech.isCompletedExceptionally());

        speechService.speak("第二次。", neutral);

        assertEquals(2, ttsClient.initializeCount);
        assertEquals(1, ttsClient.requests.size());
        assertEquals("第二次。", ttsClient.requests.getFirst().getText());
    }

    @Test
    void speakDoesNotTruncateAlreadyPreparedLongText() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer);
        VoiceProfile neutral = profile("neutral", "neutral.wav");
        String longBubbleText = "博士，天气和课程提醒我都看着。".repeat(20) + "最后一句也要完整念出来。";

        speechService.speak(longBubbleText, neutral);

        assertEquals(1, ttsClient.requests.size());
        assertEquals(longBubbleText, ttsClient.requests.getFirst().getText());
        assertTrue(ttsClient.requests.getFirst().getText().contains("最后一句也要完整念出来。"));
    }

    @Test
    void newRequestInvalidatesOldRequest() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer);
        VoiceProfile neutral = profile("neutral", "neutral.wav");

        speechService.speak("旧回复。", neutral);
        Path oldAudio = outputAudio("old.wav");
        speechService.speak("新回复。", neutral);

        ttsClient.synthesisFutures.getFirst().complete(new FileTtsAudioResult(oldAudio));

        assertTrue(audioPlayer.playedFiles.isEmpty());
        assertFalse(Files.exists(oldAudio));
    }

    @Test
    void oldLateResultDoesNotPlay() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer);
        VoiceProfile neutral = profile("neutral", "neutral.wav");

        speechService.speak("第一条。", neutral);
        CompletableFuture<TtsAudioResult> firstFuture = ttsClient.synthesisFutures.getFirst();
        speechService.speak("第二条。", neutral);

        firstFuture.complete(new FileTtsAudioResult(outputAudio("late.wav")));

        assertTrue(audioPlayer.playedFiles.isEmpty());
    }

    private VoiceProfile profile(String id, String fileName) throws IOException {
        Path audioPath = tempDir.resolve(fileName);
        Files.write(audioPath, "RIFF".getBytes(StandardCharsets.US_ASCII));
        return new VoiceProfile(id, audioPath, "参考文本。", "zh");
    }

    private Path outputAudio(String fileName) throws IOException {
        Path audioPath = tempDir.resolve(fileName);
        Files.write(audioPath, "RIFF".getBytes(StandardCharsets.US_ASCII));
        return audioPath;
    }

    private static final class FakeTtsClient implements VoiceSynthesisClient {
        private final List<TtsRequest> requests = new ArrayList<>();
        private final List<CompletableFuture<TtsAudioResult>> synthesisFutures = new ArrayList<>();
        private int checkCount;
        private int initializeCount;
        private int initializationFailuresRemaining;

        @Override
        public CompletableFuture<Void> checkAvailable() {
            checkCount++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> initializeModels() {
            initializeCount++;
            if (initializationFailuresRemaining > 0) {
                initializationFailuresRemaining--;
                return CompletableFuture.failedFuture(new RuntimeException("models are still loading"));
            }

            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<TtsAudioResult> synthesize(TtsRequest request) {
            requests.add(request);
            CompletableFuture<TtsAudioResult> future = new CompletableFuture<>();
            synthesisFutures.add(future);
            return future;
        }
    }

    private static final class FakeAudioPlayer implements VoiceAudioPlayer {
        private final List<Path> playedFiles = new ArrayList<>();
        private final List<CompletableFuture<Void>> playbackFutures = new ArrayList<>();
        private int stopCount;

        @Override
        public void play(Path audioFile) {
            playAndWait(audioFile);
        }

        @Override
        public CompletableFuture<Void> playAndWait(Path audioFile) {
            playedFiles.add(audioFile);
            CompletableFuture<Void> future = new CompletableFuture<>();
            playbackFutures.add(future);
            return future;
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }
}
