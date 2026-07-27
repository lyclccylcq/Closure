package app.voice.model;

import java.util.Locale;

/**
 * 可露希尔语音合成使用的固定情绪集合。
 */
public enum SpeechEmotion {
    NEUTRAL,
    QUESTION,
    CHEERFUL,
    TEASING,
    SERIOUS;

    public static SpeechEmotion fromString(String value) {
        if (value == null || value.isBlank()) {
            return NEUTRAL;
        }

        try {
            return SpeechEmotion.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return NEUTRAL;
        }
    }
}
