package app.voice;

import app.voice.model.FileTtsAudioResult;
import app.voice.model.TtsAudioResult;
import app.voice.model.TtsRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One complete hosted WAV per utterance; no synthesis on readiness and no automatic retries. */
public final class FishAudioClient implements VoiceSynthesisClient {
    static final int MAX_WAV_BYTES = 32 * 1024 * 1024;
    private static final long FISH_STREAM_RIFF_SIZE = 0xffff_ff24L;
    private static final long FISH_STREAM_DATA_SIZE = 0xffff_ff00L;
    private final FishAudioConfig config;
    private final HttpClient httpClient;
    private final Path temporaryDirectory;

    public FishAudioClient(FishAudioConfig config) {
        this(config, null);
    }

    FishAudioClient(FishAudioConfig config, Path temporaryDirectory) {
        this.config = Objects.requireNonNull(config, "config");
        this.temporaryDirectory = temporaryDirectory;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public CompletableFuture<Void> checkAvailable() {
        // Construction validates configuration only. Authentication/reference access remain unverified.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> initializeModels() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<TtsAudioResult> synthesize(TtsRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.getText().codePointCount(0, request.getText().length()) > 2000) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException("Fish 单次朗读不能超过 2000 字。"));
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(config.getBaseUri().resolve("/v1/tts"))
                .timeout(config.getRequestTimeout())
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("model", config.getModel())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(request.getText()), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<TtsAudioResult> completion = new CompletableFuture<>();
        AtomicReference<InputStream> body = new AtomicReference<>();
        System.out.println("CLOSURE_FISH_SYNTHESIS_REQUEST");
        CompletableFuture<HttpResponse<InputStream>> headers = httpClient.sendAsync(httpRequest,
                HttpResponse.BodyHandlers.ofInputStream());
        completion.whenComplete((audio, error) -> {
            if (error != null) {
                headers.cancel(true);
                closeQuietly(body.get());
            }
        });
        CompletableFuture.delayedExecutor(config.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> completion.completeExceptionally(
                        new VoiceSynthesisException("Fish 语音请求超时，请稍后手动重试。")));
        headers.whenComplete((response, error) -> {
            if (error != null) {
                completion.completeExceptionally(new VoiceSynthesisException("无法连接 Fish 语音服务，请检查网络后手动重试。"));
                return;
            }
            InputStream stream = response.body();
            body.set(stream);
            if (completion.isDone()) {
                closeQuietly(stream);
                return;
            }
            Thread.ofVirtual().name("fish-audio-response").start(() -> {
                Path temporary = null;
                try (stream) {
                    if (response.statusCode() != 200) throw statusError(response.statusCode());
                    long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (declaredLength > MAX_WAV_BYTES) throw invalidAudio("音频超过大小上限");
                    byte[] wav = readBounded(stream, completion);
                    long originalRiffSize = wav.length >= 8 ? unsignedInt(wav, 4) : -1;
                    long originalDataSize = wav.length >= 44 && tag(wav, 36, "data") ? unsignedInt(wav, 40) : -1;
                    try {
                        validateWav(wav);
                    } catch (VoiceSynthesisException invalid) {
                        System.out.println("CLOSURE_FISH_WAV_REJECTED bytes=" + wav.length
                                + " riff_size=" + originalRiffSize + " data_size=" + originalDataSize);
                        throw invalid;
                    }
                    if (originalRiffSize == FISH_STREAM_RIFF_SIZE) {
                        System.out.println("CLOSURE_FISH_WAV_PLACEHOLDER_NORMALIZED bytes=" + wav.length);
                    }
                    if (completion.isDone()) return;
                    temporary = temporaryDirectory == null ? Files.createTempFile("closure-fish-", ".wav")
                            : Files.createTempFile(temporaryDirectory, "closure-fish-", ".wav");
                    Files.write(temporary, wav);
                    if (completion.complete(new FileTtsAudioResult(temporary))) temporary = null;
                } catch (VoiceSynthesisException exception) {
                    completion.completeExceptionally(exception);
                } catch (Exception exception) {
                    // HTTP response bodies, user text and credential-bearing exceptions are never echoed.
                    completion.completeExceptionally(new VoiceSynthesisException("读取 Fish 语音响应失败，请稍后手动重试。"));
                } finally {
                    body.compareAndSet(stream, null);
                    deleteQuietly(temporary);
                }
            });
        });
        return completion;
    }

    @Override
    public CompletableFuture<Void> synthesizeStream(TtsRequest request, Consumer<TtsAudioResult> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        CompletableFuture<TtsAudioResult> rendered = synthesize(request);
        Object deliveryLock = new Object();
        CompletableFuture<Void> delivered = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (deliveryLock) {
                    return super.cancel(mayInterruptIfRunning);
                }
            }
        };
        delivered.whenComplete((unused, error) -> {
            if (error != null) rendered.cancel(true);
        });
        rendered.whenComplete((audio, error) -> {
            synchronized (deliveryLock) {
                if (error != null) {
                    delivered.completeExceptionally(error);
                } else if (delivered.isDone()) {
                    deleteAudio(audio);
                } else {
                    try {
                        consumer.accept(audio);
                        delivered.complete(null);
                    } catch (RuntimeException | Error exception) {
                        deleteAudio(audio);
                        delivered.completeExceptionally(exception);
                    }
                }
            }
        });
        return delivered;
    }

    private String requestJson(String text) {
        boolean steadyS2 = FishAudioConfig.FREE_MODEL.equals(config.getModel());
        // One cue for the whole reply, without switching speaker references or per-sentence emotions.
        // These documented controls reduce variation; they do not lock the generated voice/prosody.
        String spokenText = steadyS2 ? "[natural conversational delivery, steady tone] " + text : text;
        String continuity = steadyS2
                ? "\"chunk_length\":300,\"min_chunk_length\":100,\"condition_on_previous_chunks\":true,"
                : "";
        return "{\"text\":\"" + escapeJson(spokenText) + "\",\"reference_id\":\"" + config.getReferenceId()
                + "\",\"format\":\"wav\",\"sample_rate\":44100,\"latency\":\"normal\","
                + "\"temperature\":" + (steadyS2 ? "0.5" : "0.7") + ",\"top_p\":0.7,"
                + continuity + "\"prosody\":{\"speed\":1.0}}";
    }

    private static byte[] readBounded(InputStream stream, CompletableFuture<?> completion) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int count; (count = stream.read(buffer)) != -1; ) {
            if (completion.isDone()) throw new IOException("Request no longer active");
            if (count > MAX_WAV_BYTES - output.size()) throw invalidAudio("音频超过大小上限");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /** Accept complete PCM WAV, repairing only conventional unknown-length streaming headers at EOF. */
    static void validateWav(byte[] wav) {
        if (wav.length < 44 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")) {
            throw invalidAudio("不是有效的 WAV");
        }
        long riffSize = unsignedInt(wav, 4);
        // Fish's documented streaming header uses this exact size pair even for
        // buffered responses. Repair it only after readBounded reaches clean HTTP EOF.
        if (riffSize == FISH_STREAM_RIFF_SIZE) {
            if (wav.length <= 44 || !tag(wav, 12, "fmt ") || unsignedInt(wav, 16) != 16
                    || !tag(wav, 36, "data") || unsignedInt(wav, 40) != FISH_STREAM_DATA_SIZE) {
                throw invalidAudio("Fish 流式 WAV 占位头不完整");
            }
            writeInt(wav, 4, wav.length - 8L);
            writeInt(wav, 40, wav.length - 44L);
            riffSize = wav.length - 8L;
        }
        if (!unknownLength(riffSize) && riffSize != wav.length - 8L) throw invalidAudio("WAV 长度不完整");
        boolean format = false;
        boolean data = false;
        int offset = 12;
        while (offset < wav.length) {
            if (wav.length - offset < 8) throw invalidAudio("WAV 区块不完整");
            long size = unsignedInt(wav, offset + 4);
            boolean isData = tag(wav, offset, "data");
            if (isData && unknownLength(size)) {
                size = wav.length - offset - 8L;
                writeInt(wav, offset + 4, size);
            }
            long next = offset + 8L + size + (size & 1);
            if (next > wav.length) throw invalidAudio("WAV 区块被截断");
            if (tag(wav, offset, "fmt ")) {
                if (format || size < 16 || unsignedShort(wav, offset + 8) != 1
                        || unsignedShort(wav, offset + 10) != 1
                        || unsignedInt(wav, offset + 12) != 44100
                        || unsignedInt(wav, offset + 16) != 88200
                        || unsignedShort(wav, offset + 20) != 2
                        || unsignedShort(wav, offset + 22) != 16) {
                    throw invalidAudio("仅支持 44.1kHz 单声道 PCM16 WAV");
                }
                format = true;
            } else if (isData) {
                if (!format || data || size == 0 || (size & 1) != 0) throw invalidAudio("WAV 没有完整 PCM 音频");
                data = true;
            }
            offset = (int) next;
        }
        if (!format || !data) throw invalidAudio("WAV 缺少声音数据");
        if (unknownLength(riffSize)) writeInt(wav, 4, wav.length - 8L);
    }

    private static boolean unknownLength(long size) { return size == 0xffffffffL || size == 0x7fffffffL; }
    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                | ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24));
    }
    private static void writeInt(byte[] bytes, int offset, long number) {
        for (int index = 0; index < 4; index++) bytes[offset + index] = (byte) (number >>> (8 * index));
    }
    private static boolean tag(byte[] bytes, int offset, String value) {
        for (int index = 0; index < 4; index++) if (bytes[offset + index] != value.charAt(index)) return false;
        return true;
    }
    private static VoiceSynthesisException invalidAudio(String reason) {
        return new VoiceSynthesisException("Fish 返回的音频不可播放：" + reason + "。");
    }
    private static VoiceSynthesisException statusError(int code) {
        String reason = switch (code) {
            case 401 -> "API 凭据无效或已过期，请检查本机配置";
            case 402 -> "API 额度不足，请检查 Fish 账户额度";
            case 403 -> "无权调用该音源或模型，请检查音源权限";
            case 404 -> "音源或接口不存在，请检查 reference_id 和服务地址";
            case 429 -> "请求过于频繁，请稍后手动重试";
            default -> code >= 500 ? "服务暂时不可用，请稍后手动重试" : "请求被拒绝，请检查音源和请求设置";
        };
        return new VoiceSynthesisException("Fish 语音请求失败（HTTP " + code + "）：" + reason + "。");
    }
    private static void closeQuietly(InputStream stream) {
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
    }
    private static void deleteAudio(TtsAudioResult audio) {
        if (audio instanceof FileTtsAudioResult file) deleteQuietly(file.getAudioFile());
    }
    private static void deleteQuietly(Path path) {
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
    private static String escapeJson(String text) {
        StringBuilder escaped = new StringBuilder();
        for (char value : text.toCharArray()) {
            switch (value) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (value < 32) escaped.append(String.format("\\u%04x", (int) value));
                    else escaped.append(value);
                }
            }
        }
        return escaped.toString();
    }
}
