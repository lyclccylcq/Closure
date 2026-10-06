package app.voice;

import app.service.AudioPlayerService;
import app.service.SpeechService;
import app.service.VoiceAudioPlayer;
import app.voice.model.SpeechEmotion;
import javafx.application.Platform;
import javafx.scene.media.MediaPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Actual client/service integration, optionally including native JavaFX playback. */
public final class RemoteStreamingSmoke {
    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        var config = GptSovitsConfig.fromProperties(properties);
        var registry = VoiceProfileRegistry.fromConfig(config, System.out::println);
        if (!config.isRemoteServer() || !config.isStreaming()) throw new IllegalStateException("Expected remote streaming");
        boolean play = args.length > 2 && args[2].equals("--play");
        String text = args.length > 3 ? args[3] : "博士，现在由服务器为你朗读。";
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        AtomicLong firstAudio = new AtomicLong();
        AtomicLong firstPlaying = new AtomicLong();
        List<Path> temporary = new CopyOnWriteArrayList<>();
        AudioPlayerService nativePlayer = play ? new AudioPlayerService() : null;
        var field = AudioPlayerService.class.getDeclaredField("currentPlayer");
        field.setAccessible(true);
        VoiceAudioPlayer sink = new VoiceAudioPlayer() {
            public void play(Path path) { playAndWait(path); }
            public void stop() { if (nativePlayer != null) nativePlayer.stop(); }
            public CompletableFuture<Void> playAndWait(Path path) {
                firstAudio.compareAndSet(0, System.nanoTime());
                temporary.add(path);
                try {
                    Files.copy(path, output.resolve(String.format("%02d.wav", temporary.size())),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception error) { return CompletableFuture.failedFuture(error); }
                if (nativePlayer == null) return CompletableFuture.completedFuture(null);
                var future = nativePlayer.playAndWait(path);
                Platform.runLater(() -> {
                    try {
                        MediaPlayer player = (MediaPlayer) field.get(nativePlayer);
                        if (player == null) return;
                        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
                            firstPlaying.compareAndSet(0, System.nanoTime());
                        } else {
                            player.setOnPlaying(() -> firstPlaying.compareAndSet(0, System.nanoTime()));
                        }
                    } catch (Exception error) { future.completeExceptionally(error); }
                });
                return future;
            }
        };
        var service = new SpeechService(new GptSovitsClient(config), sink, registry);
        try {
            var profile = registry.get(SpeechEmotion.NEUTRAL);
            service.warmUp(profile).get(120, TimeUnit.SECONDS);
            long started = System.nanoTime();
            service.speak(text, profile).get(120, TimeUnit.SECONDS);
            if (firstAudio.get() == 0 || (play && firstPlaying.get() == 0)) {
                throw new IllegalStateException("Audio did not start");
            }
            if (temporary.stream().anyMatch(Files::exists)) throw new IllegalStateException("Temporary WAV leaked");
            String json = "{\"first_audio_seconds\":" + (firstAudio.get()-started)/1_000_000_000.0
                    + ",\"first_playing_seconds\":" + (play ? (firstPlaying.get()-started)/1_000_000_000.0 : "null")
                    + ",\"text_chars\":" + text.codePointCount(0, text.length())
                    + ",\"fragments\":" + temporary.size() + ",\"temporary_audio_cleaned\":true}";
            Files.writeString(output.resolve("timing.json"), json + "\n");
            System.out.println(json);
        } finally {
            service.stop();
            if (play) Platform.exit();
        }
    }
}
