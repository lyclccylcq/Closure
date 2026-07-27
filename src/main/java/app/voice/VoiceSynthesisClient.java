package app.voice;

import app.voice.model.TtsRequest;
import app.voice.model.TtsAudioResult;

import java.util.concurrent.CompletableFuture;

/**
 * TTS 客户端抽象，方便 SpeechService 在测试中替换真实 GPT-SoVITS HTTP 调用。
 */
public interface VoiceSynthesisClient {
    CompletableFuture<Void> checkAvailable();

    CompletableFuture<Void> initializeModels();

    CompletableFuture<TtsAudioResult> synthesize(TtsRequest request);
}
