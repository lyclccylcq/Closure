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
    void fixedPhraseRoutePreservesPlaybackLifecycleAndOrdinarySpeechUsesNormalSynthesis() throws Exception {
        FakeTtsClient client = new FakeTtsClient();
        FakeAudioPlayer player = new FakeAudioPlayer();
        SpeechService service = SpeechService.forHostedVoice(client, player);
        VoiceProfile hosted = new VoiceProfile("fish-closure", tempDir.resolve("no-local-reference"), "Hosted", "zh");
        CompletableFuture<Void> fixed = service.speakFixedPhrase("别戳啦，我在呢。", hosted);
        assertEquals(1, client.fixedRequests.size());
        Path copy = outputAudio("fixed-copy.wav");
        client.synthesisFutures.getFirst().complete(new FileTtsAudioResult(copy));
        assertEquals(List.of(copy), player.playedFiles);
        player.playbackFutures.getFirst().complete(null);
        fixed.join();
        assertFalse(Files.exists(copy));
        service.speak("别戳啦，我在呢。", hosted);
        assertEquals(2, client.requests.size());
        assertEquals(1, client.fixedRequests.size());
        service.stop();
    }

    @Test
    void hostedReadinessDoesNotSynthesizeAndForegroundSpeechNeedsNoLocalReference() throws Exception {
        FakeTtsClient client = new FakeTtsClient();
        FakeAudioPlayer player = new FakeAudioPlayer();
        SpeechService service = SpeechService.forHostedVoice(client, player);
        VoiceProfile hosted = new VoiceProfile("fish-closure", tempDir.resolve("no-local-wav"),
                "Hosted voice reference", "zh");

        service.warmUp(hosted).join();
        service.warmUp(hosted).join();
        assertTrue(service.isReady());
        assertEquals(1, client.initializeCount);
        assertEquals(0, client.checkCount);
        assertTrue(client.requests.isEmpty());

        CompletableFuture<Void> spoken = service.speak("第一句。第二句？", hosted);
        assertEquals(1, client.requests.size());
        assertEquals("第一句。第二句？", client.requests.getFirst().getText());
        Path audio = outputAudio("hosted.wav");
        client.synthesisFutures.getFirst().complete(new FileTtsAudioResult(audio));
        assertEquals(List.of(audio), player.playedFiles);
        player.playbackFutures.getFirst().complete(null);
        spoken.join();
        assertFalse(Files.exists(audio));
    }

    @Test
    void hostedStopCancelsPendingDownloadEvenWithoutStreamingPlayback() {
        CompletableFuture<Void> download = new CompletableFuture<>();
        VoiceSynthesisClient client = new VoiceSynthesisClient() {
            public CompletableFuture<Void> checkAvailable() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> initializeModels() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<TtsAudioResult> synthesize(TtsRequest request) {
                throw new AssertionError("Expected cancellable whole-WAV delivery");
            }
            public CompletableFuture<Void> synthesizeStream(TtsRequest request, Consumer<TtsAudioResult> sink) {
                return download;
            }
        };
        SpeechService service = SpeechService.forHostedVoice(client, new FakeAudioPlayer());
        VoiceProfile hosted = new VoiceProfile("fish-closure", tempDir.resolve("hosted-reference"),
                "Hosted voice reference", "zh");
        CompletableFuture<Void> speech = service.speak("尚在生成。", hosted);
        service.stop();
        assertTrue(download.isCancelled());
        assertTrue(speech.isCompletedExceptionally());
    }

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
    void continuousPlaybackCopiesAndDeletesFragmentsThenWaitsForTheFinalSample() throws Exception {
        var client=new StreamingTtsClient();
        var player=new ContinuousAudioPlayer();
        var service=new SpeechService(client,player);
        var speech=service.speak("连续朗读。",profile("neutral","neutral.wav"));
        Path first=outputAudio("pcm-first.wav");
        Path second=outputAudio("pcm-second.wav");
        client.consumer.accept(new FileTtsAudioResult(first));
        client.consumer.accept(new FileTtsAudioResult(second));
        client.stream.complete(null);
        assertEquals(1,player.opens);
        assertEquals(List.of(first,second),player.copied);
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertFalse(speech.isDone(),"Download and buffer writes must not finish speech before hardware playback ends");
        player.drained.complete(null);
        speech.join();
    }

    @Test
    void firstQwenSpeechSharesWarmupAndWaitsForWholeWavAndDrain() throws Exception {
        StreamingTtsClient client = new StreamingTtsClient();
        client.acceptCompleteRequests = true;
        client.available = new CompletableFuture<>();
        ContinuousAudioPlayer player = new ContinuousAudioPlayer();
        SpeechService service = new SpeechService(client, player, true);
        VoiceProfile profile = profile("neutral", "neutral.wav");

        CompletableFuture<Void> warmup = service.warmUp(profile);
        CompletableFuture<Void> first = service.speak("工程部在线。博士，你好。", profile);
        assertEquals(1, client.healthChecks);
        assertTrue(client.completeRequests.isEmpty());
        assertEquals(0, player.opens);

        client.available.complete(null);
        warmup.join();
        assertEquals(List.of("工程部在线。博士，你好。"),
                client.completeRequests.stream().map(TtsRequest::getText).toList());
        assertEquals(0, player.opens, "The whole render must finish before output opens");

        Path greeting = outputAudio("complete-greeting.wav");
        client.completeResponses.getFirst().complete(new FileTtsAudioResult(greeting));
        assertEquals(1, player.opens);
        assertEquals(List.of(greeting), player.copied);
        assertFalse(first.isDone());
        player.drained.complete(null);
        first.join();
        assertFalse(Files.exists(greeting));

        service.speak("接下来走普通流式播放。", profile);
        assertEquals("接下来走普通流式播放。", client.request.getText());
        assertEquals(1, client.completeRequests.size());
        service.stop();
    }

    @Test
    void supersededFirstQwenRenderCannotPlayOneLateFileOrMarkPlaybackComplete() throws Exception {
        StreamingTtsClient client = new StreamingTtsClient();
        client.acceptCompleteRequests = true;
        ContinuousAudioPlayer player = new ContinuousAudioPlayer();
        SpeechService service = new SpeechService(client, player, true);
        VoiceProfile profile = profile("neutral", "neutral.wav");

        CompletableFuture<Void> stale = service.speak("旧请求。", profile);
        CompletableFuture<Void> current = service.speak("用户的新请求。", profile);
        assertEquals(2, client.completeRequests.size());
        Path newerAudio = outputAudio("newer.wav");
        client.completeResponses.get(1).complete(new FileTtsAudioResult(newerAudio));

        Path lateAudio = outputAudio("late-whole.wav");
        client.completeResponses.getFirst().complete(new FileTtsAudioResult(lateAudio));
        stale.join();
        assertFalse(Files.exists(lateAudio));
        assertEquals(List.of(newerAudio), player.copied);
        assertFalse(current.isDone(), "The newer speech still owns its output");
        assertEquals(1, player.opens);
        service.stop();

        service.speak("完整播放前的再一次尝试。", profile);
        assertEquals(3, client.completeRequests.size());
    }

    @Test
    void qwenReadyWaitCanBeRecreatedAfterItFails() throws Exception {
        StreamingTtsClient client = new StreamingTtsClient();
        SpeechService service = new SpeechService(client, new ContinuousAudioPlayer(), true);
        VoiceProfile missing = new VoiceProfile("missing", tempDir.resolve("missing.wav"), "参考文本。", "zh");

        assertTrue(service.warmUp(missing).isCompletedExceptionally());
        service.warmUp(profile("neutral", "neutral.wav")).join();
        assertTrue(service.isReady());
        assertEquals(1, client.healthChecks);
    }

    @Test
    void stoppingContinuousPlaybackDeletesBlockedQueuedAndLateFragments() throws Exception {
        var client=new StreamingTtsClient();
        var player=new ContinuousAudioPlayer();
        player.copyImmediately=false;
        var service=new SpeechService(client,player);
        service.speak("停止测试。",profile("neutral","neutral.wav"));
        Path first=outputAudio("pcm-blocked.wav");
        Path queued=outputAudio("pcm-queued.wav");
        client.consumer.accept(new FileTtsAudioResult(first));
        client.consumer.accept(new FileTtsAudioResult(queued));
        service.stop();
        assertTrue(client.stream.isCancelled());
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(queued));
        Path late=outputAudio("pcm-late.wav");
        client.consumer.accept(new FileTtsAudioResult(late));
        assertFalse(Files.exists(late));
        assertEquals(List.of(first),player.copied);
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
        private final List<TtsRequest> fixedRequests = new ArrayList<>();
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

        @Override
        public CompletableFuture<Void> synthesizeFixedPhraseStream(TtsRequest request, Consumer<TtsAudioResult> consumer) {
            fixedRequests.add(request);
            return VoiceSynthesisClient.super.synthesizeFixedPhraseStream(request, consumer);
        }
    }

    private static final class StreamingTtsClient implements VoiceSynthesisClient {
        private final CompletableFuture<Void> stream = new CompletableFuture<>();
        private Consumer<TtsAudioResult> consumer;
        private TtsRequest request;
        private CompletableFuture<Void> available = CompletableFuture.completedFuture(null);
        private int healthChecks;
        private boolean acceptCompleteRequests;
        private final List<TtsRequest> completeRequests = new ArrayList<>();
        private final List<CompletableFuture<TtsAudioResult>> completeResponses = new ArrayList<>();
        public boolean supportsStreaming() { return true; }
        public CompletableFuture<Void> checkAvailable() { healthChecks++; return available; }
        public CompletableFuture<Void> initializeModels() { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<TtsAudioResult> synthesize(TtsRequest request) {
            if (!acceptCompleteRequests) throw new AssertionError("Expected streaming synthesis");
            completeRequests.add(request);
            var response = new CompletableFuture<TtsAudioResult>();
            completeResponses.add(response);
            return response;
        }
        public CompletableFuture<Void> synthesizeStream(TtsRequest request, Consumer<TtsAudioResult> consumer) {
            this.request = request;
            this.consumer = consumer;
            return stream;
        }
    }

    private static final class ContinuousAudioPlayer implements VoiceAudioPlayer {
        private final List<Path> copied=new ArrayList<>();
        private final List<CompletableFuture<Void>> copies=new ArrayList<>();
        private final CompletableFuture<Void> drained=new CompletableFuture<>();
        private boolean copyImmediately=true;
        private int opens;
        private VoiceAudioStream active;
        public void play(Path path){throw new AssertionError("Expected continuous PCM");}
        public CompletableFuture<Void> playAndWait(Path path){throw new AssertionError("Expected continuous PCM");}
        public void stop(){if(active!=null)active.cancel();}
        public VoiceAudioStream openStream(){
            opens++;
            active=new VoiceAudioStream(){
                public CompletableFuture<Void> append(Path path){
                    copied.add(path);
                    var future=new CompletableFuture<Void>();
                    copies.add(future);
                    if(copyImmediately)future.complete(null);
                    return future;
                }
                public CompletableFuture<Void> finish(){return drained;}
                public void cancel(){for(var future:copies)future.cancel(false);drained.cancel(false);}
            };
            return active;
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
