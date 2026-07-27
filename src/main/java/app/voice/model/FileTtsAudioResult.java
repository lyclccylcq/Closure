package app.voice.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 文件模式 TTS 合成结果。
 */
public final class FileTtsAudioResult implements TtsAudioResult {
    private final Path audioFile;

    public FileTtsAudioResult(Path audioFile) {
        this.audioFile = Objects.requireNonNull(audioFile, "audioFile");
    }

    public Path getAudioFile() {
        return audioFile;
    }
}
