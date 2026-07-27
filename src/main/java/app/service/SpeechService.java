package app.service;

import app.voice.GptSovitsClient;
import app.voice.GptSovitsConfig;
import app.voice.VoiceProfileRegistry;
import app.voice.VoiceSynthesisClient;
import app.voice.VoiceSynthesisException;
import app.voice.model.SpeechSegment;
import app.voice.model.TtsRequest;
import app.voice.model.VoiceProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * 文字转语音业务编排。
 * 负责初始化 GPT-SoVITS、合成 WAV，并交给 JavaFX 播放器播放。
 */
public final class SpeechService implements AssistantSpeechService {
    private final VoiceSynthesisClient ttsClient;
    private final VoiceAudioPlayer audioPlayerService;
    private final VoiceProfileRegistry voiceProfileRegistry;
    private final Throwable unavailableCause;
    private CompletableFuture<Void> initializationFuture;
    private CompletableFuture<Void> activeSpeechFuture;
    private long speechGeneration;

    public SpeechService(VoiceSynthesisClient ttsClient, VoiceAudioPlayer audioPlayerService) {
        this(ttsClient, audioPlayerService, null);
    }

    public SpeechService(
            VoiceSynthesisClient ttsClient,
            VoiceAudioPlayer audioPlayerService,
            VoiceProfileRegistry voiceProfileRegistry
    ) {
        this.ttsClient = Objects.requireNonNull(ttsClient, "ttsClient");
        this.audioPlayerService = Objects.requireNonNull(audioPlayerService, "audioPlayerService");
        this.voiceProfileRegistry = voiceProfileRegistry;
        unavailableCause = null;
    }

    private SpeechService(Throwable unavailableCause) {
        ttsClient = null;
        audioPlayerService = new AudioPlayerService();
        voiceProfileRegistry = null;
        this.unavailableCause = Objects.requireNonNull(unavailableCause, "unavailableCause");
    }

    public static SpeechService fromEnvironment() {
        try {
            GptSovitsConfig config = GptSovitsConfig.fromEnvironment();
            return new SpeechService(
                    new GptSovitsClient(config),
                    new AudioPlayerService(),
                    VoiceProfileRegistry.fromConfig(config, message -> System.out.println("语音 profile 警告：" + message))
            );
        } catch (RuntimeException exception) {
            return new SpeechService(exception);
        }
    }

    @Override
    public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
        long generation = startNewSpeechRequest();

        if (unavailableCause != null) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "语音服务不可用：" + unavailableCause.getMessage(),
                    unavailableCause
            ));
        }
        Objects.requireNonNull(profile, "profile");
        if (!Files.isRegularFile(profile.getReferenceAudioPath())) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "参考音频不存在：" + profile.getReferenceAudioPath()
            ));
        }

        CompletableFuture<Void> speechFuture = initializeOnce()
                .thenCompose(unused -> ttsClient.synthesize(new TtsRequest(text, "zh", profile, profile.getSpeedFactor())))
                .thenCompose(audioFile -> {
                    if (isCurrentSpeech(generation)) {
                        return playAndDelete(generation, audioFile);
                    }
                    deleteTempAudio(audioFile);
                    return CompletableFuture.completedFuture(null);
                });

        rememberActiveSpeech(generation, speechFuture);
        return speechFuture;
    }

    @Override
    public CompletableFuture<Void> speakSegments(List<SpeechSegment> segments) {
        long generation = startNewSpeechRequest();

        if (unavailableCause != null) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "语音服务不可用：" + unavailableCause.getMessage(),
                    unavailableCause
            ));
        }
        if (segments == null || segments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (voiceProfileRegistry == null || !voiceProfileRegistry.isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> speechFuture = initializeOnce()
                .thenCompose(unused -> speakSegmentChain(generation, segments, 0));

        rememberActiveSpeech(generation, speechFuture);
        return speechFuture;
    }

    @Override
    public void stop() {
        CompletableFuture<Void> speechFuture;
        synchronized (this) {
            speechGeneration++;
            speechFuture = activeSpeechFuture;
            activeSpeechFuture = null;
        }

        if (speechFuture != null) {
            speechFuture.cancel(true);
        }
        audioPlayerService.stop();
    }

    private synchronized CompletableFuture<Void> initializeOnce() {
        if (initializationFuture == null
                || initializationFuture.isCancelled()
                || initializationFuture.isCompletedExceptionally()) {
            initializationFuture = ttsClient.initializeModels();
        }

        return initializationFuture;
    }

    private CompletableFuture<Void> speakSegmentChain(
            long generation,
            List<SpeechSegment> segments,
            int segmentIndex
    ) {
        if (!isCurrentSpeech(generation) || segmentIndex >= segments.size()) {
            return CompletableFuture.completedFuture(null);
        }

        SpeechSegment segment = segments.get(segmentIndex);
        String text = SpeechTextSanitizer.sanitizeSegment(segment.getText());
        if (text.isBlank()) {
            return speakSegmentChain(generation, segments, segmentIndex + 1);
        }

        VoiceProfile profile = voiceProfileRegistry.get(segment.getEmotion());
        if (profile == null) {
            return speakSegmentChain(generation, segments, segmentIndex + 1);
        }

        CompletableFuture<Void> currentSegmentFuture = ttsClient
                .synthesize(new TtsRequest(text, "zh", profile, profile.getSpeedFactor()))
                .thenCompose(audioFile -> {
                    if (!isCurrentSpeech(generation)) {
                        deleteTempAudio(audioFile);
                        return CompletableFuture.completedFuture(null);
                    }

                    return playAndDelete(generation, audioFile);
                })
                .exceptionally(error -> {
                    if (!isCancellation(error)) {
                        System.out.println("语音分段朗读失败：" + safeMessage(error));
                    }
                    return null;
                });

        return currentSegmentFuture.thenCompose(unused -> speakSegmentChain(generation, segments, segmentIndex + 1));
    }

    private CompletableFuture<Void> playAndDelete(long generation, Path audioFile) {
        if (!isCurrentSpeech(generation)) {
            deleteTempAudio(audioFile);
            return CompletableFuture.completedFuture(null);
        }

        return audioPlayerService.playAndWait(audioFile)
                .whenComplete((unused, error) -> deleteTempAudio(audioFile));
    }

    private void deleteTempAudio(Path audioFile) {
        if (audioFile == null) {
            return;
        }

        try {
            Files.deleteIfExists(audioFile);
        } catch (IOException exception) {
            System.out.println("删除临时语音失败：" + exception.getMessage());
        }
    }

    private long startNewSpeechRequest() {
        CompletableFuture<Void> previousSpeechFuture;
        long generation;
        synchronized (this) {
            speechGeneration++;
            generation = speechGeneration;
            previousSpeechFuture = activeSpeechFuture;
            activeSpeechFuture = null;
        }

        if (previousSpeechFuture != null) {
            previousSpeechFuture.cancel(true);
        }
        audioPlayerService.stop();
        return generation;
    }

    private synchronized boolean isCurrentSpeech(long generation) {
        return speechGeneration == generation;
    }

    private synchronized void rememberActiveSpeech(long generation, CompletableFuture<Void> speechFuture) {
        if (speechGeneration != generation) {
            speechFuture.cancel(true);
            return;
        }

        activeSpeechFuture = speechFuture;
        speechFuture.whenComplete((unused, error) -> clearActiveSpeech(generation));
    }

    private synchronized void clearActiveSpeech(long generation) {
        if (speechGeneration == generation) {
            activeSpeechFuture = null;
        }
    }

    private boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    private String safeMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }

        String message = current == null ? null : current.getMessage();
        if (message == null || message.isBlank()) {
            return current == null ? "unknown" : current.getClass().getSimpleName();
        }

        return message;
    }
}
