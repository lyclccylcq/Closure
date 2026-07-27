package app.voice.model;

/**
 * 一段适合连续朗读的文本及其语气。
 */
public final class SpeechSegment {
    private final String text;
    private final SpeechEmotion emotion;

    public SpeechSegment(String text, SpeechEmotion emotion) {
        this.text = text == null ? "" : text.trim();
        this.emotion = emotion == null ? SpeechEmotion.NEUTRAL : emotion;
    }

    public String getText() {
        return text;
    }

    public SpeechEmotion getEmotion() {
        return emotion;
    }
}
