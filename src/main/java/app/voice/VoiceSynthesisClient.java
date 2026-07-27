package app.voice;

import app.voice.model.TtsRequest;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * TTS 客户端抽象，方便 SpeechService 在测试中替换真实 GPT-SoVITS HTTP 调用。
 */
public interface VoiceSynthesisClient {
    CompletableFuture<Void> initializeModels();

    CompletableFuture<Path> synthesize(TtsRequest request);
}
