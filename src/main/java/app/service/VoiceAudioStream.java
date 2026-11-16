package app.service;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** One utterance on one output device, even when its WAV fragments arrive separately. */
public interface VoiceAudioStream {
    /** Completes when the fragment has been copied; its temporary file can then be deleted. */
    CompletableFuture<Void> append(Path wav);

    /** Completes only after the final sample has played. */
    CompletableFuture<Void> finish();

    void cancel();
}
