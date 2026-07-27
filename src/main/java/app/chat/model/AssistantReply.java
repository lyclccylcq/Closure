package app.chat.model;

import app.service.SpeechTextSanitizer;
import app.voice.model.SpeechSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 模型返回的结构化助手回复。
 * displayText 给聊天窗口显示，speechSegments 只给 TTS 使用。
 */
public final class AssistantReply {
    public static final int MAX_SEGMENTS = 5;

    private final String displayText;
    private final List<SpeechSegment> speechSegments;

    public AssistantReply(String displayText, List<SpeechSegment> speechSegments) {
        if (displayText == null || displayText.isBlank()) {
            throw new IllegalArgumentException("displayText 不能为空");
        }

        this.displayText = displayText.trim();
        this.speechSegments = normalizeSegments(speechSegments);
    }

    public String getDisplayText() {
        return displayText;
    }

    public List<SpeechSegment> getSpeechSegments() {
        return speechSegments;
    }

    private List<SpeechSegment> normalizeSegments(List<SpeechSegment> sourceSegments) {
        if (sourceSegments == null || sourceSegments.isEmpty()) {
            return List.of();
        }

        List<SpeechSegment> normalizedSegments = new ArrayList<>();
        int remainingCodePoints = SpeechTextSanitizer.MAX_TOTAL_CODE_POINTS;
        for (SpeechSegment segment : sourceSegments) {
            if (segment == null || normalizedSegments.size() >= MAX_SEGMENTS || remainingCodePoints <= 0) {
                break;
            }

            String text = SpeechTextSanitizer.sanitizeSegment(segment.getText(), remainingCodePoints);
            if (text.isBlank()) {
                continue;
            }

            normalizedSegments.add(new SpeechSegment(text, segment.getEmotion()));
            remainingCodePoints -= text.codePointCount(0, text.length());
        }

        return List.copyOf(normalizedSegments);
    }

    @Override
    public String toString() {
        return "AssistantReply{displayText='" + displayText + "', speechSegments=" + speechSegments.size() + "}";
    }
}
