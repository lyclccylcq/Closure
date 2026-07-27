package app.voice;

import app.voice.model.SpeechEmotion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeechEmotionTest {
    @Test
    void parsesNormalValue() {
        assertEquals(SpeechEmotion.CHEERFUL, SpeechEmotion.fromString("CHEERFUL"));
    }

    @Test
    void ignoresCase() {
        assertEquals(SpeechEmotion.TEASING, SpeechEmotion.fromString("teasing"));
    }

    @Test
    void unknownFallsBackToNeutral() {
        assertEquals(SpeechEmotion.NEUTRAL, SpeechEmotion.fromString("angry"));
    }

    @Test
    void nullFallsBackToNeutral() {
        assertEquals(SpeechEmotion.NEUTRAL, SpeechEmotion.fromString(null));
    }
}
