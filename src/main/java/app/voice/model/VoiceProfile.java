package app.voice.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * GPT-SoVITS 的一组参考音频配置。
 */
public final class VoiceProfile {
    private static final double DEFAULT_SPEED_FACTOR = 0.95;
    private static final int DEFAULT_TOP_K = 8;
    private static final double DEFAULT_TOP_P = 0.85;
    private static final double DEFAULT_TEMPERATURE = 0.7;
    private static final int DEFAULT_SEED = 42;
    private static final double DEFAULT_REPETITION_PENALTY = 1.35;

    private final String id;
    private final Path referenceAudioPath;
    private final String promptText;
    private final String promptLanguage;
    private final double speedFactor;
    private final int topK;
    private final double topP;
    private final double temperature;
    private final int seed;
    private final double repetitionPenalty;

    public VoiceProfile(String id, Path referenceAudioPath, String promptText, String promptLanguage) {
        this(
                id,
                referenceAudioPath,
                promptText,
                promptLanguage,
                DEFAULT_SPEED_FACTOR,
                DEFAULT_TOP_K,
                DEFAULT_TOP_P,
                DEFAULT_TEMPERATURE,
                DEFAULT_SEED,
                DEFAULT_REPETITION_PENALTY
        );
    }

    public VoiceProfile(
            String id,
            Path referenceAudioPath,
            String promptText,
            String promptLanguage,
            double speedFactor,
            int topK,
            double topP,
            double temperature
    ) {
        this(
                id,
                referenceAudioPath,
                promptText,
                promptLanguage,
                speedFactor,
                topK,
                topP,
                temperature,
                DEFAULT_SEED,
                DEFAULT_REPETITION_PENALTY
        );
    }

    public VoiceProfile(
            String id,
            Path referenceAudioPath,
            String promptText,
            String promptLanguage,
            double speedFactor,
            int topK,
            double topP,
            double temperature,
            int seed,
            double repetitionPenalty
    ) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (promptText == null || promptText.isBlank()) {
            throw new IllegalArgumentException("promptText 不能为空");
        }
        if (promptLanguage == null || promptLanguage.isBlank()) {
            throw new IllegalArgumentException("promptLanguage 不能为空");
        }
        if (speedFactor <= 0) {
            throw new IllegalArgumentException("speedFactor 必须大于 0");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }
        if (topP <= 0) {
            throw new IllegalArgumentException("topP 必须大于 0");
        }
        if (temperature <= 0) {
            throw new IllegalArgumentException("temperature 必须大于 0");
        }
        if (seed < 0) {
            throw new IllegalArgumentException("seed 不能小于 0");
        }
        if (repetitionPenalty <= 0) {
            throw new IllegalArgumentException("repetitionPenalty 必须大于 0");
        }

        this.id = id.trim();
        this.referenceAudioPath = Objects.requireNonNull(referenceAudioPath, "referenceAudioPath");
        this.promptText = promptText.trim();
        this.promptLanguage = promptLanguage.trim();
        this.speedFactor = speedFactor;
        this.topK = topK;
        this.topP = topP;
        this.temperature = temperature;
        this.seed = seed;
        this.repetitionPenalty = repetitionPenalty;
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

    public double getSpeedFactor() {
        return speedFactor;
    }

    public int getTopK() {
        return topK;
    }

    public double getTopP() {
        return topP;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getSeed() {
        return seed;
    }

    public double getRepetitionPenalty() {
        return repetitionPenalty;
    }
}
