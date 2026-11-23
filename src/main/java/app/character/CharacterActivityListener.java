package app.character;

import app.voice.model.SpeechEmotion;

/** Optional UI bridge. The returned completion is safe to call on success, error or disposal. */
public interface CharacterActivityListener {
    CharacterActivityListener NONE = new CharacterActivityListener() { };
    default Runnable conversationOpened() { return () -> { }; }
    default Runnable thinkingStarted() { return () -> { }; }
    default void replyEmotion(SpeechEmotion emotion) { }
}
