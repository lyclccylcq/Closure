package app.service;

import app.voice.VoiceProfileRegistry;
import app.voice.VoiceSynthesisClient;
import app.voice.model.SpeechEmotion;
import app.voice.model.SpeechSegment;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void speaksMultipleSegmentsInOrder() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = speechService(ttsClient, audioPlayer);

        CompletableFuture<Void> result = speechService.speakSegments(List.of(
                new SpeechSegment("第一段。", SpeechEmotion.NEUTRAL),
                new SpeechSegment("第二段？", SpeechEmotion.QUESTION)
        ));

        assertEquals(1, ttsClient.requests.size());
        Path firstAudio = outputAudio("first.wav");
        ttsClient.synthesisFutures.getFirst().complete(firstAudio);
        assertEquals(List.of(firstAudio), audioPlayer.playedFiles);
        assertEquals(1, ttsClient.requests.size());

        audioPlayer.playbackFutures.getFirst().complete(null);
        assertEquals(2, ttsClient.requests.size());

        Path secondAudio = outputAudio("second.wav");
        ttsClient.synthesisFutures.get(1).complete(secondAudio);
        assertEquals(List.of(firstAudio, secondAudio), audioPlayer.playedFiles);
        audioPlayer.playbackFutures.get(1).complete(null);

        assertTrue(result.isDone());
    }

    @Test
    void usesProfileForEachEmotion() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = speechService(ttsClient, audioPlayer);

        speechService.speakSegments(List.of(
                new SpeechSegment("普通。", SpeechEmotion.NEUTRAL),
                new SpeechSegment("真的？", SpeechEmotion.QUESTION)
        ));

        ttsClient.synthesisFutures.getFirst().complete(outputAudio("neutral-output.wav"));
        audioPlayer.playbackFutures.getFirst().complete(null);
        ttsClient.synthesisFutures.get(1).complete(outputAudio("question-output.wav"));

        assertEquals("neutral", ttsClient.requests.getFirst().getVoiceProfile().getId());
        assertEquals("question", ttsClient.requests.get(1).getVoiceProfile().getId());
    }

    @Test
    void newRequestInvalidatesOldRequest() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = speechService(ttsClient, audioPlayer);

        speechService.speakSegments(List.of(new SpeechSegment("旧回复。", SpeechEmotion.NEUTRAL)));
        Path oldAudio = outputAudio("old.wav");
        speechService.speakSegments(List.of(new SpeechSegment("新回复。", SpeechEmotion.NEUTRAL)));

        ttsClient.synthesisFutures.getFirst().complete(oldAudio);

        assertTrue(audioPlayer.playedFiles.isEmpty());
        assertFalse(Files.exists(oldAudio));
    }

    @Test
    void oldLateResultDoesNotPlay() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = speechService(ttsClient, audioPlayer);

        speechService.speakSegments(List.of(new SpeechSegment("第一条。", SpeechEmotion.NEUTRAL)));
        CompletableFuture<Path> firstFuture = ttsClient.synthesisFutures.getFirst();
        speechService.speakSegments(List.of(new SpeechSegment("第二条。", SpeechEmotion.NEUTRAL)));

        firstFuture.complete(outputAudio("late.wav"));

        assertTrue(audioPlayer.playedFiles.isEmpty());
    }

    @Test
    void segmentFailureContinuesWithNextSegment() throws Exception {
        FakeTtsClient ttsClient = new FakeTtsClient();
        FakeAudioPlayer audioPlayer = new FakeAudioPlayer();
        SpeechService speechService = speechService(ttsClient, audioPlayer);

        CompletableFuture<Void> result = speechService.speakSegments(List.of(
                new SpeechSegment("坏掉。", SpeechEmotion.NEUTRAL),
                new SpeechSegment("继续。", SpeechEmotion.QUESTION)
        ));

        ttsClient.synthesisFutures.getFirst().completeExceptionally(new RuntimeException("tts failed"));
        assertEquals(2, ttsClient.requests.size());

        Path secondAudio = outputAudio("continue.wav");
        ttsClient.synthesisFutures.get(1).complete(secondAudio);
        audioPlayer.playbackFutures.getFirst().complete(null);

        assertTrue(result.isDone());
        assertEquals(List.of(secondAudio), audioPlayer.playedFiles);
    }

    private SpeechService speechService(FakeTtsClient ttsClient, FakeAudioPlayer audioPlayer) throws IOException {
        VoiceProfile neutral = profile("neutral", "neutral-ref.wav");
        VoiceProfile question = profile("question", "question-ref.wav");
        VoiceProfileRegistry registry = new VoiceProfileRegistry(
                Map.of(
                        SpeechEmotion.NEUTRAL, neutral,
                        SpeechEmotion.QUESTION, question
                ),
                message -> {
                }
        );
        return new SpeechService(ttsClient, audioPlayer, registry);
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
        private final List<CompletableFuture<Path>> synthesisFutures = new ArrayList<>();

        @Override
        public CompletableFuture<Void> initializeModels() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Path> synthesize(TtsRequest request) {
            requests.add(request);
            CompletableFuture<Path> future = new CompletableFuture<>();
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
