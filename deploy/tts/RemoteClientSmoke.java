package app.voice;

import app.service.SpeechService;
import app.service.VoiceAudioPlayer;
import app.voice.model.SpeechEmotion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Exercise Closure's real HTTP/service path with a file sink for the audio. */
public final class RemoteClientSmoke {
    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        GptSovitsConfig config = GptSovitsConfig.fromProperties(properties);
        VoiceProfileRegistry registry = VoiceProfileRegistry.fromConfig(config, System.out::println);
        if (!config.isRemoteServer() || !registry.isAvailable()) {
            throw new IllegalStateException("Remote server profile is unavailable");
        }
        var managedTunnel = args.length > 2 && args[2].equals("--managed")
                ? GptSovitsServerProcess.startConfiguredAsync(System.out::println).get(30, TimeUnit.SECONDS)
                : java.util.Optional.<GptSovitsServerProcess>empty();
        try {
        Path output = Path.of(args[1]);
        Path[] consumed = new Path[1];
        VoiceAudioPlayer sink = new VoiceAudioPlayer() {
            public void play(Path path) { }
            public void stop() { }
            public CompletableFuture<Void> playAndWait(Path path) {
                try {
                    Files.copy(path, output, StandardCopyOption.REPLACE_EXISTING);
                    consumed[0] = path;
                    return CompletableFuture.completedFuture(null);
                } catch (Exception error) {
                    return CompletableFuture.failedFuture(error);
                }
            }
        };
        SpeechService service = new SpeechService(new GptSovitsClient(config), sink, registry);
        var profile = registry.get(SpeechEmotion.NEUTRAL);
        service.warmUp(profile).get(120, TimeUnit.SECONDS);
        service.speak("博士，现在由服务器为你朗读。", profile).get(120, TimeUnit.SECONDS);
        if (!service.isReady() || Files.size(output) <= 44 || Files.exists(consumed[0])) {
            throw new IllegalStateException("Real synthesis or temporary audio cleanup failed");
        }
        System.out.println("Closure Java client: remote profile, warmup, synthesis and temporary WAV cleanup passed.");
        } finally {
            managedTunnel.ifPresent(GptSovitsServerProcess::close);
        }
    }
}
