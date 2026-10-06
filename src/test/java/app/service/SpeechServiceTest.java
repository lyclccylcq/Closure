package app.service;

import app.voice.VoiceSynthesisClient;
import app.voice.VoiceProfileRegistry;
import app.voice.model.FileTtsAudioResult;
import app.voice.model.SpeechEmotion;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void remoteWarmupAndSpeechWorkWithoutLocalReferenceAndDeleteReturnedAudio() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        VoiceProfile remoteProfile = new VoiceProfile("neutral",
                tempDir.resolve("server-only/neutral.wav"), "参考文本。", "zh");
        VoiceProfileRegistry registry = new VoiceProfileRegistry(
                Map.of(SpeechEmotion.NEUTRAL, remoteProfile), message -> { }, true);
        SpeechService speechService = new SpeechService(ttsClient, audioPlayer, registry);

        CompletableFuture<Void> warmup = speechService.warmUp(registry.get(SpeechEmotion.NEUTRAL));
        Path warmupAudio = outputAudio("remote-warmup.wav");
        ttsClient.synthesisFutures.getFirst().complete(new FileTtsAudioResult(warmupAudio));
        warmup.join();

        assertTrue(speechService.isReady());
        assertFalse(Files.exists(warmupAudio));
        assertTrue(audioPlayer.playedFiles.isEmpty());

        CompletableFuture<Void> speech = speechService.speak("博士，远程朗读。", remoteProfile);
        Path speechAudio = outputAudio("remote-speech.wav");
        ttsClient.synthesisFutures.get(1).complete(new FileTtsAudioResult(speechAudio));
        audioPlayer.playbackFutures.getFirst().complete(null);
        speech.join();

        assertEquals(1, ttsClient.checkCount);
        assertEquals(1, ttsClient.initializeCount);
        assertEquals(2, ttsClient.requests.size());
        assertEquals(remoteProfile.getReferenceAudioPath(),
                ttsClient.requests.getFirst().getVoiceProfile().getReferenceAudioPath());
        assertEquals(List.of(speechAudio), audioPlayer.playedFiles);
        assertFalse(Files.exists(speechAudio));
    }

    @Test
    void defaultLocalModeRejectsMissingReferencesBeforeNetworkRequests() {
        FakeTtsClient ttsClient = new FakeTtsClient();
        SpeechService speechService = new SpeechService(ttsClient, new FakeAudioPlayer());
        VoiceProfile missingProfile = new VoiceProfile("neutral",
                tempDir.resolve("missing.wav"), "参考文本。", "zh");

        assertTrue(speechService.warmUp(missingProfile).isCompletedExceptionally());
        assertTrue(speechService.speak("博士，测试。", missingProfile).isCompletedExceptionally());
        assertEquals(0, ttsClient.checkCount);
        assertEquals(0, ttsClient.initializeCount);
        assertTrue(ttsClient.requests.isEmpty());
    }

    @Test
    void remoteModeStillRejectsNullProfiles() {
        VoiceProfileRegistry registry = new VoiceProfileRegistry(Map.of(), message -> { }, true);
        SpeechService speechService = new SpeechService(new FakeTtsClient(), new FakeAudioPlayer(), registry);

        assertThrows(NullPointerException.class, () -> speechService.warmUp(null));
        assertThrows(NullPointerException.class, () -> speechService.speak("博士，测试。", null));
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
    void streamingPlaysFirstFragmentBeforeCompletionAndPreservesOrder() throws Exception {
        StreamingTtsClient client = new StreamingTtsClient();
        FakeAudioPlayer player = new FakeAudioPlayer();
        SpeechService service = new SpeechService(client, player);
        var speech = service.speak("第一句。第二句。", profile("neutral", "neutral.wav"));
        Path first = outputAudio("first-fragment.wav");
        Path second = outputAudio("second-fragment.wav");

        client.consumer.accept(new FileTtsAudioResult(first));
        assertEquals(List.of(first), player.playedFiles);
        assertFalse(speech.isDone());
        client.consumer.accept(new FileTtsAudioResult(second));
        assertEquals(List.of(first), player.playedFiles);
        client.stream.complete(null);
        assertFalse(speech.isDone(), "Speech includes completion of playback, not just download");

        player.playbackFutures.getFirst().complete(null);
        assertEquals(List.of(first, second), player.playedFiles);
        assertFalse(Files.exists(first));
        player.playbackFutures.get(1).complete(null);
        speech.join();
        assertFalse(Files.exists(second));
        assertEquals("第一句。第二句。", client.request.getText());
    }

    @Test
    void stopCancelsTheStreamAndDeletesQueuedAndLateFragments() throws Exception {
        StreamingTtsClient client = new StreamingTtsClient();
        FakeAudioPlayer player = new FakeAudioPlayer();
        SpeechService service = new SpeechService(client, player);
        service.speak("第一句。第二句。", profile("neutral", "neutral.wav"));
        Path first = outputAudio("stopped-first.wav");
        Path queued = outputAudio("stopped-queued.wav");
        client.consumer.accept(new FileTtsAudioResult(first));
        client.consumer.accept(new FileTtsAudioResult(queued));

        service.stop();
        assertTrue(client.stream.isCancelled());
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(queued));
        Path late = outputAudio("stopped-late.wav");
        client.consumer.accept(new FileTtsAudioResult(late));
        assertFalse(Files.exists(late));
        assertEquals(List.of(first), player.playedFiles);
    }

    @Test
    void streamFailureStopsPlaybackAndCleansUpRemainingFragments() throws Exception {
        StreamingTtsClient client = new StreamingTtsClient();
        FakeAudioPlayer player = new FakeAudioPlayer();
        SpeechService service = new SpeechService(client, player);
        var speech = service.speak("第一句。第二句。", profile("neutral", "neutral.wav"));
        Path first = outputAudio("failed-first.wav");
        Path queued = outputAudio("failed-queued.wav");
        client.consumer.accept(new FileTtsAudioResult(first));
        client.consumer.accept(new FileTtsAudioResult(queued));
        client.stream.completeExceptionally(new IOException("Server connection lost"));

        assertTrue(speech.isCompletedExceptionally());
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(queued));
        assertEquals(List.of(first), player.playedFiles);
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

    private static final class StreamingTtsClient implements VoiceSynthesisClient {
        private final CompletableFuture<Void> stream = new CompletableFuture<>();
        private Consumer<TtsAudioResult> consumer;
        private TtsRequest request;
        public boolean supportsStreaming() { return true; }
        public CompletableFuture<Void> checkAvailable() { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<Void> initializeModels() { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<TtsAudioResult> synthesize(TtsRequest request) { throw new AssertionError(); }
        public CompletableFuture<Void> synthesizeStream(TtsRequest request, Consumer<TtsAudioResult> consumer) {
            this.request = request;
            this.consumer = consumer;
            return stream;
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
            playbackFutures.forEach(future -> future.cancel(true));
        }
    }
}
