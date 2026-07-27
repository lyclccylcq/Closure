package app.service;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 使用 JavaFX MediaPlayer 播放本地音频文件。
 */
public final class AudioPlayerService implements VoiceAudioPlayer {
    private static final Object FX_START_LOCK = new Object();
    private static boolean fxStarted;

    private MediaPlayer currentPlayer;
    private CompletableFuture<Void> currentPlaybackFuture;

    @Override
    public void play(Path audioFile) {
        playAndWait(audioFile);
    }

    @Override
    public CompletableFuture<Void> playAndWait(Path audioFile) {
        Objects.requireNonNull(audioFile, "audioFile");
        CompletableFuture<Void> playbackFuture = new CompletableFuture<>();
        runOnFxThread(() -> playNow(audioFile, playbackFuture));
        return playbackFuture;
    }

    @Override
    public void stop() {
        runOnFxThread(this::stopNow);
    }

    private void playNow(Path audioFile, CompletableFuture<Void> playbackFuture) {
        stopNow();

        try {
            Media media = new Media(audioFile.toUri().toString());
            MediaPlayer player = new MediaPlayer(media);
            currentPlayer = player;
            currentPlaybackFuture = playbackFuture;

            player.setOnEndOfMedia(() -> {
                disposePlayer(player);
                playbackFuture.complete(null);
            });
            player.setOnError(() -> {
                Throwable error = player.getError();
                if (error != null) {
                    System.out.println("语音播放失败：" + error.getMessage());
                    playbackFuture.completeExceptionally(error);
                } else {
                    playbackFuture.completeExceptionally(new IllegalStateException("语音播放失败"));
                }
                disposePlayer(player);
            });
            player.play();
        } catch (RuntimeException exception) {
            System.out.println("创建语音播放器失败：" + exception.getMessage());
            if (currentPlayer != null) {
                MediaPlayer player = currentPlayer;
                currentPlayer = null;
                player.dispose();
            }
            currentPlaybackFuture = null;
            playbackFuture.completeExceptionally(exception);
        }
    }

    private void stopNow() {
        if (currentPlayer != null) {
            MediaPlayer player = currentPlayer;
            currentPlayer = null;
            player.stop();
            player.dispose();
        }
        if (currentPlaybackFuture != null) {
            CompletableFuture<Void> playbackFuture = currentPlaybackFuture;
            currentPlaybackFuture = null;
            playbackFuture.cancel(true);
        }
    }

    private void disposePlayer(MediaPlayer player) {
        if (player == null) {
            return;
        }

        if (currentPlayer == player) {
            currentPlayer = null;
        }
        if (currentPlaybackFuture != null) {
            currentPlaybackFuture = null;
        }

        player.dispose();
    }

    private void runOnFxThread(Runnable action) {
        ensureFxStarted();
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static void ensureFxStarted() {
        synchronized (FX_START_LOCK) {
            if (fxStarted) {
                return;
            }

            try {
                Platform.startup(() -> Platform.setImplicitExit(false));
            } catch (IllegalStateException exception) {
                Platform.setImplicitExit(false);
            }

            fxStarted = true;
        }
    }
}
