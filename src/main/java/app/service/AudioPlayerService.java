package app.service;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 使用 JavaFX MediaPlayer 播放本地音频文件。
 */
public final class AudioPlayerService {
    private static final Object FX_START_LOCK = new Object();
    private static boolean fxStarted;

    private MediaPlayer currentPlayer;

    public void play(Path audioFile) {
        Objects.requireNonNull(audioFile, "audioFile");
        runOnFxThread(() -> playNow(audioFile));
    }

    public void stop() {
        runOnFxThread(this::stopNow);
    }

    private void playNow(Path audioFile) {
        stopNow();

        try {
            Media media = new Media(audioFile.toUri().toString());
            MediaPlayer player = new MediaPlayer(media);
            currentPlayer = player;

            player.setOnEndOfMedia(() -> disposePlayer(player));
            player.setOnError(() -> {
                Throwable error = player.getError();
                if (error != null) {
                    System.out.println("语音播放失败：" + error.getMessage());
                }
                disposePlayer(player);
            });
            player.play();
        } catch (RuntimeException exception) {
            System.out.println("创建语音播放器失败：" + exception.getMessage());
            stopNow();
        }
    }

    private void stopNow() {
        if (currentPlayer != null) {
            MediaPlayer player = currentPlayer;
            currentPlayer = null;
            player.stop();
            player.dispose();
        }
    }

    private void disposePlayer(MediaPlayer player) {
        if (player == null) {
            return;
        }

        if (currentPlayer == player) {
            currentPlayer = null;
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
