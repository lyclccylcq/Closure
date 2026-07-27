package app.voice;

import app.voice.model.TtsRequest;
import app.voice.model.VoiceProfile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * GPT-SoVITS HTTP 客户端。
 * 所有网络请求都通过 HttpClient.sendAsync 执行，不阻塞 UI 线程。
 */
public final class GptSovitsClient {
    private static final String SET_GPT_WEIGHTS_PATH = "/set_gpt_weights";
    private static final String SET_SOVITS_WEIGHTS_PATH = "/set_sovits_weights";
    private static final String TTS_PATH = "/tts";

    private final GptSovitsConfig config;
    private final HttpClient httpClient;

    public GptSovitsClient(GptSovitsConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(config, "config").getConnectTimeout())
                .build());
    }

    public GptSovitsClient(GptSovitsConfig config, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public CompletableFuture<Void> initializeModels() {
        return setWeights(SET_GPT_WEIGHTS_PATH, config.getGptWeightPath())
                .thenCompose(unused -> setWeights(SET_SOVITS_WEIGHTS_PATH, config.getSovitsWeightPath()));
    }

    public CompletableFuture<Path> synthesize(TtsRequest request) {
        Objects.requireNonNull(request, "request");

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(endpoint(TTS_PATH))
                .timeout(config.getRequestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofString(toTtsJson(request), StandardCharsets.UTF_8))
                .build();

        return httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> requireWavResponse(response, "语音合成"))
                .thenApply(this::writeTempWav);
    }

    private CompletableFuture<Void> setWeights(String endpointPath, Path weightPath) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(endpoint(endpointPath + "?weights_path=" + urlEncode(weightPath.toString())))
                .timeout(config.getRequestTimeout())
                .GET()
                .build();

        return httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> requireTextResponse(response, endpointPath))
                .thenApply(unused -> null);
    }

    private byte[] requireWavResponse(HttpResponse<byte[]> response, String operationName) {
        if (response.statusCode() != 200) {
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            throw new VoiceSynthesisException(operationName + "失败，HTTP " + response.statusCode() + ": " + errorBody);
        }

        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new VoiceSynthesisException(operationName + "失败：GPT-SoVITS 返回了空 WAV 内容");
        }

        return body;
    }

    private String requireTextResponse(HttpResponse<String> response, String operationName) {
        if (response.statusCode() != 200) {
            throw new VoiceSynthesisException(
                    operationName + " 失败，HTTP " + response.statusCode() + ": " + response.body()
            );
        }

        return response.body();
    }

    private Path writeTempWav(byte[] wavBytes) {
        try {
            Path outputPath = Files.createTempFile("closure-gpt-sovits-", ".wav");
            Files.write(outputPath, wavBytes);
            return outputPath;
        } catch (IOException exception) {
            throw new CompletionException(new VoiceSynthesisException("保存 GPT-SoVITS WAV 到临时目录失败", exception));
        }
    }

    private URI endpoint(String endpointPath) {
        String base = config.getBaseUri().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!endpointPath.startsWith("/")) {
            endpointPath = "/" + endpointPath;
        }

        return URI.create(base + endpointPath);
    }

    private String toTtsJson(TtsRequest request) {
        VoiceProfile profile = request.getVoiceProfile();

        return "{"
                + "\"text\":\"" + escapeJson(request.getText()) + "\","
                + "\"text_lang\":\"" + escapeJson(request.getTextLanguage()) + "\","
                + "\"ref_audio_path\":\"" + escapeJson(profile.getReferenceAudioPath().toString()) + "\","
                + "\"prompt_text\":\"" + escapeJson(profile.getPromptText()) + "\","
                + "\"prompt_lang\":\"" + escapeJson(profile.getPromptLanguage()) + "\","
                + "\"top_k\":15,"
                + "\"top_p\":1.0,"
                + "\"temperature\":1.0,"
                + "\"text_split_method\":\"cut5\","
                + "\"batch_size\":1,"
                + "\"speed_factor\":" + request.getSpeedFactor() + ","
                + "\"media_type\":\"wav\","
                + "\"streaming_mode\":false,"
                + "\"parallel_infer\":true"
                + "}";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }

        return escaped.toString();
    }
}
