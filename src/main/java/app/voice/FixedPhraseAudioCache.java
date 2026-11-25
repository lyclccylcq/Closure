package app.voice;

import app.voice.model.FileTtsAudioResult;
import app.voice.model.TtsAudioResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Persistent audio reuse for the finite public touch phrases, never generated chat. */
final class FixedPhraseAudioCache {
    private final Path directory;
    private final Path temporaryDirectory;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();

    FixedPhraseAudioCache(Path directory, Path temporaryDirectory) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.temporaryDirectory = temporaryDirectory;
    }

    CompletableFuture<TtsAudioResult> synthesize(String identity,
                                                Supplier<CompletableFuture<TtsAudioResult>> generate) {
        String key = key(identity);
        CompletableFuture<byte[]> candidate = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = pending.putIfAbsent(key, candidate);
        CompletableFuture<byte[]> shared = existing == null ? candidate : existing;
        if (existing == null) {
            Thread.ofVirtual().name("fish-fixed-phrase-load").start(() -> loadOrGenerate(key, candidate, generate));
        }
        CompletableFuture<TtsAudioResult> playback = new CompletableFuture<>();
        // Cancel only this waiter. A shared fixed clip can finish caching without playing late audio.
        shared.whenCompleteAsync((wav, error) -> {
            if (error != null) {
                playback.completeExceptionally(error);
                return;
            }
            if (playback.isDone()) return;
            Path copy = null;
            try {
                copy = temporaryDirectory == null ? Files.createTempFile("closure-fish-fixed-", ".wav")
                        : Files.createTempFile(temporaryDirectory, "closure-fish-fixed-", ".wav");
                Files.write(copy, wav);
                if (playback.complete(new FileTtsAudioResult(copy))) copy = null;
            } catch (IOException exception) {
                playback.completeExceptionally(new VoiceSynthesisException("准备固定台词音频失败，请检查本机临时目录。"));
            } finally {
                deleteQuietly(copy);
            }
        }, task -> Thread.ofVirtual().name("fish-fixed-phrase-copy").start(task));
        return playback;
    }

    private void loadOrGenerate(String key, CompletableFuture<byte[]> shared,
                                Supplier<CompletableFuture<TtsAudioResult>> generate) {
        byte[] cached = readValidated(directory.resolve(key + ".wav"));
        if (cached != null) {
            System.out.println("CLOSURE_FISH_FIXED_CACHE_HIT");
            shared.complete(cached);
            pending.remove(key, shared);
            return;
        }
        try {
            generate.get().whenComplete((audio, error) -> {
                if (error != null) {
                    pending.remove(key, shared);
                    shared.completeExceptionally(error);
                    return;
                }
                Path generated = audio instanceof FileTtsAudioResult file ? file.getAudioFile() : null;
                try {
                    byte[] wav = generated == null ? null : readValidated(generated);
                    if (wav == null) throw new VoiceSynthesisException("固定台词合成没有返回完整的 WAV 音频。");
                    boolean persisted = persist(key, wav);
                    if (!persisted) System.out.println("CLOSURE_FISH_FIXED_CACHE_MEMORY_ONLY");
                    deleteQuietly(generated);
                    generated = null;
                    shared.complete(wav);
                    // Disk failure retains this successful clip for the six fixed phrases in this session.
                    if (persisted) pending.remove(key, shared);
                } catch (RuntimeException exception) {
                    pending.remove(key, shared);
                    shared.completeExceptionally(new VoiceSynthesisException("保存固定台词音频失败。"));
                } finally {
                    deleteQuietly(generated);
                }
            });
        } catch (RuntimeException exception) {
            pending.remove(key, shared);
            shared.completeExceptionally(new VoiceSynthesisException("生成固定台词音频失败。"));
        }
    }

    private static byte[] readValidated(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > FishAudioClient.MAX_WAV_BYTES) {
                return null;
            }
            try (var input = Files.newInputStream(path)) {
                byte[] wav = input.readNBytes(FishAudioClient.MAX_WAV_BYTES + 1);
                if (wav.length > FishAudioClient.MAX_WAV_BYTES) return null;
                FishAudioClient.validateWav(wav);
                return wav;
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private boolean persist(String key, byte[] wav) {
        Path staging = null;
        try {
            Files.createDirectories(directory);
            staging = Files.createTempFile(directory, ".fixed-", ".wav");
            Files.write(staging, wav);
            Files.move(staging, directory.resolve(key + ".wav"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            staging = null;
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        } finally {
            deleteQuietly(staging);
        }
    }

    private static String key(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("fish-fixed-v1\n" + identity).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }
}
