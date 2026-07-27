package app.voice.model;

import java.util.Objects;

/**
 * 一次文字转语音请求。
 */
public final class TtsRequest {
    private final String text;
    private final String textLanguage;
    private final VoiceProfile voiceProfile;
    private final double speedFactor;

    public TtsRequest(String text, String textLanguage, VoiceProfile voiceProfile) {
        this(text, textLanguage, voiceProfile, Objects.requireNonNull(voiceProfile, "voiceProfile").getSpeedFactor());
    }

    public TtsRequest(String text, String textLanguage, VoiceProfile voiceProfile, double speedFactor) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        if (textLanguage == null || textLanguage.isBlank()) {
            throw new IllegalArgumentException("textLanguage 不能为空");
        }
        if (speedFactor <= 0) {
            throw new IllegalArgumentException("speedFactor 必须大于 0");
        }

        this.text = text.trim();
        this.textLanguage = textLanguage.trim();
        this.voiceProfile = Objects.requireNonNull(voiceProfile, "voiceProfile");
        this.speedFactor = speedFactor;
    }

    public String getText() {
        return text;
    }

    public String getTextLanguage() {
        return textLanguage;
    }

    public VoiceProfile getVoiceProfile() {
        return voiceProfile;
    }

    public double getSpeedFactor() {
        return speedFactor;
    }
}
