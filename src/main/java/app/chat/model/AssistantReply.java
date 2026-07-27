package app.chat.model;

import app.service.SpeechTextSanitizer;
import app.voice.model.SpeechEmotion;

/**
 * 模型返回的结构化助手回复。
 * displayText 给聊天窗口显示，speechText 是交给 TTS 的简短口语文本。
 */
public final class AssistantReply {
    public static final int MAX_SPEECH_TEXT_CODE_POINTS = 120;

    private final String displayText;
    private final String speechText;
    private final SpeechEmotion dominantEmotion;

    public AssistantReply(String displayText, String speechText, SpeechEmotion dominantEmotion) {
        if (displayText == null || displayText.isBlank()) {
            throw new IllegalArgumentException("displayText 不能为空");
        }

        this.displayText = displayText.trim();
        this.speechText = normalizeSpeechText(speechText, this.displayText);
        this.dominantEmotion = dominantEmotion == null ? SpeechEmotion.NEUTRAL : dominantEmotion;
    }

    public String getDisplayText() {
        return displayText;
    }

    public String getSpeechText() {
        return speechText;
    }

    public SpeechEmotion getDominantEmotion() {
        return dominantEmotion;
    }

    private String normalizeSpeechText(String sourceSpeechText, String fallbackDisplayText) {
        String normalizedSpeechText = SpeechTextSanitizer.sanitize(
                sourceSpeechText,
                MAX_SPEECH_TEXT_CODE_POINTS
        );
        if (!normalizedSpeechText.isBlank()) {
            return normalizedSpeechText;
        }

        return SpeechTextSanitizer.sanitize(fallbackDisplayText, MAX_SPEECH_TEXT_CODE_POINTS);
    }

    @Override
    public String toString() {
        return "AssistantReply{displayText='" + displayText
                + "', speechTextLength=" + speechText.codePointCount(0, speechText.length())
                + ", dominantEmotion=" + dominantEmotion + "}";
    }
}
