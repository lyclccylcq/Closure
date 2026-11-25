package app.service;

import app.voice.model.VoiceProfile;

import java.util.concurrent.CompletableFuture;

/**
 * 聊天朗读只依赖这个小接口，测试时不用启动真实 GPT-SoVITS 或 JavaFX MediaPlayer。
 */
public interface AssistantSpeechService {
    default CompletableFuture<Void> warmUp(VoiceProfile profile) {
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Void> speak(String text, VoiceProfile profile);

    /** Fixed UI phrases may reuse audio; local providers retain ordinary speech behavior. */
    default CompletableFuture<Void> speakFixedPhrase(String text, VoiceProfile profile) {
        return speak(text, profile);
    }

    void stop();
}
