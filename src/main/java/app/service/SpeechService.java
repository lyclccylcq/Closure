package app.service;

import app.voice.GptSovitsClient;
import app.voice.GptSovitsConfig;
import app.voice.VoiceSynthesisException;
import app.voice.model.TtsRequest;
import app.voice.model.VoiceProfile;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 文字转语音业务编排。
 * 当前阶段只提供服务能力，不主动接入聊天窗口。
 */
public final class SpeechService {
    private final GptSovitsClient ttsClient;
    private final AudioPlayerService audioPlayerService;
    private final Throwable unavailableCause;
    private CompletableFuture<Void> initializationFuture;

    public SpeechService(GptSovitsClient ttsClient, AudioPlayerService audioPlayerService) {
        this.ttsClient = Objects.requireNonNull(ttsClient, "ttsClient");
        this.audioPlayerService = Objects.requireNonNull(audioPlayerService, "audioPlayerService");
        unavailableCause = null;
    }

    private SpeechService(Throwable unavailableCause) {
        ttsClient = null;
        audioPlayerService = new AudioPlayerService();
        this.unavailableCause = Objects.requireNonNull(unavailableCause, "unavailableCause");
    }

    public static SpeechService fromEnvironment() {
        try {
            GptSovitsConfig config = GptSovitsConfig.fromEnvironment();
            return new SpeechService(new GptSovitsClient(config), new AudioPlayerService());
        } catch (RuntimeException exception) {
            return new SpeechService(exception);
        }
    }

    public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
        if (unavailableCause != null) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException(
                    "语音服务不可用：" + unavailableCause.getMessage(),
                    unavailableCause
            ));
        }

        return initializeOnce()
                .thenCompose(unused -> ttsClient.synthesize(new TtsRequest(text, "zh", profile, 1.0)))
                .thenAccept(audioPlayerService::play);
    }

    public void stop() {
        audioPlayerService.stop();
    }

    private synchronized CompletableFuture<Void> initializeOnce() {
        if (initializationFuture == null) {
            initializationFuture = ttsClient.initializeModels();
        }

        return initializationFuture;
    }
}
