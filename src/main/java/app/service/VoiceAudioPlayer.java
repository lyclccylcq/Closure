package app.service;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * 本地音频播放器抽象，方便语音编排测试避开 JavaFX MediaPlayer。
 */
public interface VoiceAudioPlayer {
    void play(Path audioFile);

    CompletableFuture<Void> playAndWait(Path audioFile);

    void stop();
}
