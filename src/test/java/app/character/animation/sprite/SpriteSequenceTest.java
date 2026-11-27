package app.character.animation.sprite;

import app.character.animation.AnimationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SpriteSequenceTest {
    @TempDir Path root;
    @Test void thinkingUsesAuthoredTransitionsAndSpeechPreemptsImmediately() throws Exception {
        SpriteModel model = SpriteModel.load(SpriteFixtures.create(root));
        var thinking = new SpriteSequence(model, AnimationType.IDLE, AnimationType.THINKING, false, 0);
        assertEquals("think_begin", thinking.sample(0).clip());
        assertEquals("think_idle", thinking.sample(200_000_000L).clip());
        assertEquals("think_idle", thinking.sample(9_000_000_000L).clip());
        var idle = new SpriteSequence(model, AnimationType.THINKING, AnimationType.IDLE, false, 0);
        assertEquals("think_exit", idle.sample(0).clip()); assertEquals("idle", idle.sample(200_000_000L).clip());
        for (var type : new AnimationType[]{AnimationType.TALK, AnimationType.THINKING_TALK}) {
            var talk = new SpriteSequence(model, AnimationType.THINKING, type, false, 0);
            assertEquals("talk", talk.sample(0).clip()); assertFalse(talk.sample(20_000_000_000L).finished());
        }
    }
    @Test void sadAndInteractionsFinishAfterTheirLastFrame() throws Exception {
        SpriteModel model = SpriteModel.load(SpriteFixtures.create(root));
        var sad = new SpriteSequence(model, AnimationType.IDLE, AnimationType.SAD, true, 0);
        assertEquals("sad_begin", sad.sample(0).clip());
        assertEquals("sad_idle", sad.sample(200_000_000L).clip());
        assertEquals("sad_exit", sad.sample(400_000_000L).clip());
        assertFalse(sad.sample(599_999_999L).finished()); assertTrue(sad.sample(600_000_000L).finished());
        assertEquals(5, sad.sample(Long.MAX_VALUE).frame());
        assertEquals("interact_1", new SpriteSequence(model, null, AnimationType.TAP, true, 0).sample(0).clip());
        assertEquals("interact_2", new SpriteSequence(model, null, AnimationType.TAP, true, 1).sample(0).clip());
    }
}
