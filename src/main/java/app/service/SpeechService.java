package app.service;

import app.voice.GptSovitsClient;
import app.voice.GptSovitsConfig;
import app.voice.VoiceProfileRegistry;
import app.voice.VoiceSynthesisClient;
import app.voice.VoiceSynthesisException;
import app.voice.model.FileTtsAudioResult;
import app.voice.model.TtsAudioResult;
import app.voice.model.TtsRequest;
import app.voice.model.VoiceProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 文字转语音业务编排。
 * 负责初始化 GPT-SoVITS、合成 WAV，并交给 JavaFX 播放器播放。
 */
public final class SpeechService implements AssistantSpeechService {
    private static final String WARMUP_TEXT = "工程部在线。";
    private static final int WARMUP_MAX_ATTEMPTS = 12;
    private static final long WARMUP_RETRY_DELAY_MILLIS = 5_000L;

    private final VoiceSynthesisClient ttsClient;
    private final VoiceAudioPlayer audioPlayerService;
    private final Throwable unavailableCause;
    private final Predicate<VoiceProfile> referenceAudioUsable;
    private CompletableFuture<Void> initializationFuture;
    private CompletableFuture<Void> activeSpeechFuture;
    private CompletableFuture<Void> activeSynthesisFuture;
    private long speechGeneration;
    private volatile boolean ready;

    public SpeechService(VoiceSynthesisClient ttsClient, VoiceAudioPlayer audioPlayerService) {
        this(ttsClient, audioPlayerService,
                profile -> Files.isRegularFile(profile.getReferenceAudioPath()));
    }

    private SpeechService(
            VoiceSynthesisClient ttsClient,
            VoiceAudioPlayer audioPlayerService,
            Predicate<VoiceProfile> referenceAudioUsable
    ) {
        this.ttsClient = Objects.requireNonNull(ttsClient, "ttsClient");
        this.audioPlayerService = Objects.requireNonNull(audioPlayerService, "audioPlayerService");
        this.referenceAudioUsable = Objects.requireNonNull(referenceAudioUsable, "referenceAudioUsable");
        unavailableCause = null;
    }

    public SpeechService(
            VoiceSynthesisClient ttsClient,
            VoiceAudioPlayer audioPlayerService,
            VoiceProfileRegistry voiceProfileRegistry
    ) {
        this(ttsClient, audioPlayerService,
                Objects.requireNonNull(voiceProfileRegistry, "voiceProfileRegistry")::isReferenceAudioUsable);
    }

    private SpeechService(Throwable unavailableCause) {
        ttsClient = null;
        audioPlayerService = new AudioPlayerService();
        this.unavailableCause = Objects.requireNonNull(unavailableCause, "unavailableCause");
        referenceAudioUsable = profile -> false;
    }

    public static SpeechService fromEnvironment() {
        try {
            GptSovitsConfig config = GptSovitsConfig.fromEnvironment();
            return new SpeechService(
                    new GptSovitsClient(config),
                    new AudioPlayerService(),
                    VoiceProfileRegistry.fromConfig(config, message -> System.out.println("GPT-SoVITS 警告：" + message))
            );
        } catch (RuntimeException exception) {
            return new SpeechService(exception);
        }
    }

    @Override
    public CompletableFuture<Void> warmUp(VoiceProfile profile) {
        return warmUp(profile, 1);
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
        String speechText = SpeechTextSanitizer.sanitizeFull(text);
        if (speechText.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        long generation = startNewSpeechRequest();

        if (unavailableCause != null) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "语音服务不可用：" + unavailableCause.getMessage(),
                    unavailableCause
            ));
        }
        Objects.requireNonNull(profile, "profile");
        if (!referenceAudioUsable.test(profile)) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "参考音频不存在：" + profile.getReferenceAudioPath()
            ));
        }

        PlaybackQueue playback = new PlaybackQueue(generation);
        CompletableFuture<Void> speechFuture = initializeOnce()
                .thenCompose(unused -> {
                    if (!isCurrentSpeech(generation)) return CompletableFuture.completedFuture(null);
                    CompletableFuture<Void> synthesis = ttsClient.synthesizeStream(new TtsRequest(
                            speechText, "zh", profile, profile.getSpeedFactor()), playback::accept);
                    if (ttsClient.supportsStreaming()) rememberSynthesis(generation, synthesis);
                    return synthesis.whenComplete((done, error) -> {
                        if (error != null && isCurrentSpeech(generation)) audioPlayerService.stop();
                    }).thenCompose(done -> playback.finished());
                });

        rememberActiveSpeech(generation, speechFuture);
        return speechFuture;
    }

    private CompletableFuture<Void> warmUp(VoiceProfile profile, int attempt) {
        if (unavailableCause != null) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "语音服务不可用：" + unavailableCause.getMessage(),
                    unavailableCause
            ));
        }
        Objects.requireNonNull(profile, "profile");
        if (!referenceAudioUsable.test(profile)) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "参考音频不存在：" + profile.getReferenceAudioPath()
            ));
        }

        return ttsClient.checkAvailable()
                .thenCompose(unused -> initializeOnce())
                .thenCompose(unused -> ttsClient.synthesize(new TtsRequest(
                        WARMUP_TEXT,
                        "zh",
                        profile,
                        profile.getSpeedFactor()
                )))
                .thenAccept(this::deleteAudioResult)
                .thenRun(() -> ready = true)
                .handle((unused, error) -> {
                    if (error == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (shouldRetryWarmUp(attempt)) {
                        return delayedWarmUp(profile, attempt + 1);
                    }
                    return CompletableFuture.<Void>failedFuture(unwrapCompletion(error));
                })
                .thenCompose(future -> future);
    }

    @Override
    public void stop() {
        CompletableFuture<Void> synthesis;
        synchronized (this) {
            speechGeneration++;
            activeSpeechFuture = null;
            synthesis = activeSynthesisFuture;
            activeSynthesisFuture = null;
        }

        if (synthesis != null) synthesis.cancel(true);
        audioPlayerService.stop();
    }

    private void rememberSynthesis(long generation, CompletableFuture<Void> synthesis) {
        synchronized (this) {
            if (speechGeneration == generation) activeSynthesisFuture = synthesis;
            else synthesis.cancel(true);
        }
        synthesis.whenComplete((unused, error) -> {
            synchronized (this) {
                if (activeSynthesisFuture == synthesis) activeSynthesisFuture = null;
            }
        });
    }

    /** Download can continue while earlier fragments play; only one fragment plays at a time. */
    private final class PlaybackQueue {
        private final long generation;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

        private PlaybackQueue(long generation) { this.generation = generation; }

        private synchronized void accept(TtsAudioResult audio) {
            if (!isCurrentSpeech(generation)) {
                deleteAudioResult(audio);
                return;
            }
            tail = tail.thenCompose(unused -> playAndDelete(generation, audio))
                    // Also delete queued fragments if an earlier playback fails or is stopped.
                    .whenComplete((unused, error) -> deleteAudioResult(audio));
        }

        private synchronized CompletableFuture<Void> finished() { return tail; }
    }

    private synchronized CompletableFuture<Void> initializeOnce() {
        CompletableFuture<Void> currentInitializationFuture = initializationFuture;
        if (initializationFuture == null || initializationFuture.isCompletedExceptionally()) {
            currentInitializationFuture = ttsClient.initializeModels();
            initializationFuture = currentInitializationFuture;
            CompletableFuture<Void> trackedInitializationFuture = currentInitializationFuture;
            currentInitializationFuture.whenComplete((unused, error) -> {
                if (error != null) {
                    clearFailedInitialization(trackedInitializationFuture);
                }
            });
        }

        return currentInitializationFuture;
    }

    private synchronized void clearFailedInitialization(CompletableFuture<Void> failedInitializationFuture) {
        if (initializationFuture == failedInitializationFuture) {
            initializationFuture = null;
        }
    }

    private CompletableFuture<Void> delayedWarmUp(VoiceProfile profile, int attempt) {
        return CompletableFuture
                .runAsync(
                        () -> {
                        },
                        CompletableFuture.delayedExecutor(WARMUP_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                )
                .thenCompose(unused -> warmUp(profile, attempt));
    }

    private boolean shouldRetryWarmUp(int attempt) {
        return attempt < WARMUP_MAX_ATTEMPTS
                && (initializationFuture == null || !initializationFuture.isCompletedExceptionally());
    }

    private CompletableFuture<Void> playAndDelete(long generation, TtsAudioResult audioResult) {
        if (!isCurrentSpeech(generation)) {
            deleteAudioResult(audioResult);
            return CompletableFuture.completedFuture(null);
        }

        if (audioResult instanceof FileTtsAudioResult fileResult) {
            Path audioFile = fileResult.getAudioFile();
            return audioPlayerService.playAndWait(audioFile)
                    .whenComplete((unused, error) -> deleteTempAudio(audioFile));
        }

        return CompletableFuture.failedFuture(new VoiceSynthesisException("暂不支持的 TTS 音频结果类型"));
    }

    private void deleteAudioResult(TtsAudioResult audioResult) {
        if (audioResult instanceof FileTtsAudioResult fileResult) {
            deleteTempAudio(fileResult.getAudioFile());
        }
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
        CompletableFuture<Void> previousSynthesis;
        long generation;
        synchronized (this) {
            speechGeneration++;
            generation = speechGeneration;
            previousSynthesis = activeSynthesisFuture;
            activeSpeechFuture = null;
            activeSynthesisFuture = null;
        }

        if (previousSynthesis != null) previousSynthesis.cancel(true);
        audioPlayerService.stop();
        return generation;
    }

    private synchronized boolean isCurrentSpeech(long generation) {
        return speechGeneration == generation;
    }

    private synchronized void rememberActiveSpeech(long generation, CompletableFuture<Void> speechFuture) {
        if (speechGeneration != generation) {
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

    private Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }

        return current == null ? new VoiceSynthesisException("未知语音错误") : current;
    }

    private String safeMessage(Throwable error) {
        Throwable current = unwrapCompletion(error);

        String message = current == null ? null : current.getMessage();
        if (message == null || message.isBlank()) {
            return current == null ? "unknown" : current.getClass().getSimpleName();
        }

        return message;
    }
}
