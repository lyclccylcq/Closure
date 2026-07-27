package app.voice.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * GPT-SoVITS 的一组参考音频配置。
 */
public final class VoiceProfile {
    private final String id;
    private final Path referenceAudioPath;
    private final String promptText;
    private final String promptLanguage;

    public VoiceProfile(String id, Path referenceAudioPath, String promptText, String promptLanguage) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (promptText == null || promptText.isBlank()) {
            throw new IllegalArgumentException("promptText 不能为空");
        }
        if (promptLanguage == null || promptLanguage.isBlank()) {
            throw new IllegalArgumentException("promptLanguage 不能为空");
        }

        this.id = id.trim();
        this.referenceAudioPath = Objects.requireNonNull(referenceAudioPath, "referenceAudioPath");
        this.promptText = promptText.trim();
        this.promptLanguage = promptLanguage.trim();
    }

    public String getId() {
        return id;
    }

    public Path getReferenceAudioPath() {
        return referenceAudioPath;
    }

    public String getPromptText() {
        return promptText;
    }

    public String getPromptLanguage() {
        return promptLanguage;
    }
}
