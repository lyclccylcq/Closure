package app.voice;

import app.service.AudioPlayerService;
import app.voice.model.FileTtsAudioResult;
import app.voice.model.TtsRequest;
import javafx.application.Platform;

import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Manual local diagnostic only. --render makes exactly one billable fixed-text request. */
public final class FishVoiceSmoke {
    private static final String TEXT = "你找到最后那一块拼图啦？居然藏在杯垫下面。行，这回算它输给你了。";

    public static void main(String[] args) {
        int exit = 0;
        try {
            if (args.length == 1 && args[0].equals("--check")) {
                checkedConfig();
                System.out.println("{\"status\":\"CONFIGURED\",\"scope\":\"local format only; authentication unverified\",\"cloud_requests\":0}");
            } else if (args.length == 2 && args[0].equals("--render")) {
                render(Path.of(args[1]).toAbsolutePath());
            } else if (args.length == 2 && args[0].equals("--playback")) {
                playback(Path.of(args[1]).toAbsolutePath());
            } else {
                throw new IllegalArgumentException("Use --check, --render output.wav, or --playback generated.wav");
            }
        } catch (Exception failure) {
            Throwable cause = failure;
            while ((cause instanceof ExecutionException || cause instanceof java.util.concurrent.CompletionException)
                    && cause.getCause() != null) cause = cause.getCause();
            // Only the application's already-redacted provider/config errors may reach the console.
            String reason = cause instanceof VoiceSynthesisException
                    ? cause.getMessage() : "Diagnostic failed (" + cause.getClass().getSimpleName() + ")";
            System.out.println("{\"status\":\"FAIL\",\"reason\":\"" + escaped(reason) + "\"}");
            exit = 1;
        } finally {
            if (args.length > 0 && args[0].equals("--playback")) Platform.exit();
        }
        System.exit(exit);
    }

    private static FishAudioConfig checkedConfig() {
        if (!FishAudioConfig.isSelected()) throw new VoiceSynthesisException("Fish backend is not explicitly selected");
        FishAudioConfig config = FishAudioConfig.fromEnvironment();
        if (!config.getBaseUri().toString().equals("https://api.fish.audio")
                || !config.getReferenceId().equals(FishAudioConfig.DEFAULT_REFERENCE_ID)) {
            throw new VoiceSynthesisException("Live diagnostic requires the selected community voice and official Fish endpoint");
        }
        return config;
    }

    private static void render(Path output) throws Exception {
        if (Files.exists(output) || !output.getFileName().toString().endsWith(".wav")) {
            throw new IllegalArgumentException("Use a new WAV output path");
        }
        Files.createDirectories(output.getParent());
        FishAudioConfig config = checkedConfig();
        FishAudioClient client = new FishAudioClient(config);
        long started = System.nanoTime();
        FileTtsAudioResult audio = (FileTtsAudioResult) client.synthesize(
                new TtsRequest(TEXT, "zh", config.getVoiceProfile()))
                .get(config.getRequestTimeout().toSeconds() + 10, TimeUnit.SECONDS);
        try {
            Files.copy(audio.getAudioFile(), output);
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
        } finally {
            Files.deleteIfExists(audio.getAudioFile());
        }
        byte[] wav = Files.readAllBytes(output); // Generated synthetic output only, never a reference sample.
        FishAudioClient.validateWav(wav);
        double seconds;
        try (var input = AudioSystem.getAudioInputStream(output.toFile())) {
            seconds = input.getFrameLength() / input.getFormat().getFrameRate();
        }
        System.out.println("{\"status\":\"GENERATED\",\"model_requested\":\"drama-3\",\"reference_id\":\""
                + config.getReferenceId() + "\",\"synthetic_text\":\"" + TEXT + "\",\"cloud_requests\":1,\"bytes\":"
                + wav.length + ",\"audio_seconds\":" + seconds + ",\"elapsed_seconds\":"
                + (System.nanoTime() - started) / 1_000_000_000.0 + ",\"sha256\":\""
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wav))
                + "\",\"user_listening_acceptance\":false}");
    }

    private static void playback(Path generated) throws Exception {
        FishAudioClient.validateWav(Files.readAllBytes(generated));
        AudioPlayerService player = new AudioPlayerService();
        try {
            CompletableFuture<Void> first = player.playAndWait(generated);
            CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(player::stop);
            boolean cancelled = false;
            try {
                first.get(5, TimeUnit.SECONDS);
            } catch (CancellationException expected) {
                cancelled = true;
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof CancellationException) cancelled = true;
                else throw failure;
            }
            if (!cancelled) throw new IllegalStateException("Playback was not cancelled");
            player.playAndWait(generated).get(45, TimeUnit.SECONDS);
            System.out.println("{\"status\":\"PASS\",\"scope\":\"native stop and full replay of saved synthetic WAV\","
                    + "\"cloud_requests\":0,\"stop_verified\":true,\"full_playback_completed\":true,\"user_listening_acceptance\":false}");
        } finally {
            player.stop();
        }
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
