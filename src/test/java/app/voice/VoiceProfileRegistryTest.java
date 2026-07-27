package app.voice;

import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceProfileRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsConfiguredProfile() throws Exception {
        VoiceProfile neutral = profile("neutral", wav("neutral.wav"));
        VoiceProfile question = profile("question", wav("question.wav"));
        VoiceProfileRegistry registry = registry(Map.of(
                SpeechEmotion.NEUTRAL, neutral,
                SpeechEmotion.QUESTION, question
        ));

        assertEquals("question", registry.get(SpeechEmotion.QUESTION).getId());
    }

    @Test
    void missingQuestionFallsBackToNeutral() throws Exception {
        VoiceProfile neutral = profile("neutral", wav("neutral.wav"));
        VoiceProfileRegistry registry = registry(Map.of(SpeechEmotion.NEUTRAL, neutral));

        assertEquals("neutral", registry.get(SpeechEmotion.QUESTION).getId());
    }

    @Test
    void missingReferenceFileFallsBackToNeutral() throws Exception {
        VoiceProfile neutral = profile("neutral", wav("neutral.wav"));
        VoiceProfile question = profile("question", tempDir.resolve("missing.wav"));
        VoiceProfileRegistry registry = registry(Map.of(
                SpeechEmotion.NEUTRAL, neutral,
                SpeechEmotion.QUESTION, question
        ));

        assertEquals("neutral", registry.get(SpeechEmotion.QUESTION).getId());
    }

    @Test
    void cheerfulAndSeriousCurrentlyUseNeutralProfile() throws Exception {
        VoiceProfile neutral = profile("neutral", wav("neutral.wav"));
        VoiceProfile cheerful = profile("cheerful", wav("cheerful.wav"));
        VoiceProfile serious = profile("serious", wav("serious.wav"));
        VoiceProfileRegistry registry = registry(Map.of(
                SpeechEmotion.NEUTRAL, neutral,
                SpeechEmotion.CHEERFUL, cheerful,
                SpeechEmotion.SERIOUS, serious
        ));

        assertEquals("neutral", registry.get(SpeechEmotion.CHEERFUL).getId());
        assertEquals("neutral", registry.get(SpeechEmotion.SERIOUS).getId());
    }

    @Test
    void unavailableWhenNeutralReferenceIsMissing() {
        List<String> warnings = new ArrayList<>();
        VoiceProfile neutral = profile("neutral", tempDir.resolve("missing-neutral.wav"));
        VoiceProfileRegistry registry = new VoiceProfileRegistry(
                Map.of(SpeechEmotion.NEUTRAL, neutral),
                warnings::add
        );

        assertFalse(registry.isAvailable());
        assertNull(registry.get(SpeechEmotion.NEUTRAL));
        assertTrue(warnings.size() <= 2);
    }

    private VoiceProfileRegistry registry(Map<SpeechEmotion, VoiceProfile> profiles) {
        return new VoiceProfileRegistry(profiles, message -> {
        });
    }

    private VoiceProfile profile(String id, Path audioPath) {
        return new VoiceProfile(id, audioPath, "参考文本。", "zh");
    }

    private Path wav(String fileName) throws IOException {
        Path path = tempDir.resolve(fileName);
        java.nio.file.Files.write(path, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return path;
    }
}
