package app.voice;

import app.voice.model.TtsRequest;
import app.voice.model.TtsAudioResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * TTS 客户端抽象，方便 SpeechService 在测试中替换真实 GPT-SoVITS HTTP 调用。
 */
public interface VoiceSynthesisClient {
    CompletableFuture<Void> checkAvailable();

    CompletableFuture<Void> initializeModels();

    CompletableFuture<TtsAudioResult> synthesize(TtsRequest request);

    default boolean supportsStreaming() { return false; }

    /** The consumer owns each delivered fragment, including temporary-file cleanup. */
    default CompletableFuture<Void> synthesizeStream(TtsRequest request, Consumer<TtsAudioResult> consumer) {
        return synthesize(request).thenAccept(consumer);
    }
}
