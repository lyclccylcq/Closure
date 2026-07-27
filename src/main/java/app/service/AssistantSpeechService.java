package app.service;

import app.voice.model.VoiceProfile;
import app.voice.model.SpeechSegment;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 聊天朗读只依赖这个小接口，测试时不用启动真实 GPT-SoVITS 或 JavaFX MediaPlayer。
 */
public interface AssistantSpeechService {
    CompletableFuture<Void> speak(String text, VoiceProfile profile);

    CompletableFuture<Void> speakSegments(List<SpeechSegment> segments);

    void stop();
}
