package app.voice;

import app.voice.model.FileTtsAudioResult;
import app.voice.model.TtsAudioResult;
import app.voice.model.TtsRequest;
import app.voice.model.VoiceProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GptSovitsClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void synthesizeSavesWavBytesWhenTtsReturnsOk() throws Exception {
        byte[] fakeWav = "RIFF-fake-WAVE".getBytes(StandardCharsets.US_ASCII);
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeResponse(exchange, 200, fakeWav);
        });

        Path outputPath = synthesizeToFile(client(), ttsRequest("博士，测试一下。"));

        assertArrayEquals(fakeWav, Files.readAllBytes(outputPath));
        assertTrue(requestBody.get().contains("\"text\":\"博士，测试一下。\""));
        assertTrue(requestBody.get().contains("\"media_type\":\"wav\""));
        assertTrue(requestBody.get().contains("\"top_k\":8"));
        assertTrue(requestBody.get().contains("\"top_p\":0.85"));
        assertTrue(requestBody.get().contains("\"temperature\":0.7"));
        assertTrue(requestBody.get().contains("\"seed\":42"));
        assertTrue(requestBody.get().contains("\"repetition_penalty\":1.35"));
        assertTrue(requestBody.get().contains("\"fragment_interval\":0.12"));
        assertTrue(requestBody.get().contains("\"parallel_infer\":true"));
        assertTrue(requestBody.get().contains("\"split_bucket\":true"));

        Files.deleteIfExists(outputPath);
    }

    @Test
    void shortTextUsesCut0() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeResponse(exchange, 200, "RIFF-short".getBytes(StandardCharsets.US_ASCII));
        });

        Path outputPath = synthesizeToFile(client(), ttsRequest("短句。"));

        assertTrue(requestBody.get().contains("\"text_split_method\":\"cut0\""));
        Files.deleteIfExists(outputPath);
    }

    @Test
    void longTextUsesCut5() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeResponse(exchange, 200, "RIFF-long".getBytes(StandardCharsets.US_ASCII));
        });

        Path outputPath = synthesizeToFile(client(), ttsRequest("长".repeat(81)));

        assertTrue(requestBody.get().contains("\"text_split_method\":\"cut5\""));
        Files.deleteIfExists(outputPath);
    }

    @Test
    void cut0UnsupportedFallsBackToCut5() throws Exception {
        byte[] fakeWav = "RIFF-fallback-cut5".getBytes(StandardCharsets.US_ASCII);
        List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            if (body.contains("\"text_split_method\":\"cut0\"")) {
                writeResponse(exchange, 400, "cut0 unsupported".getBytes(StandardCharsets.UTF_8));
                return;
            }
            writeResponse(exchange, 200, fakeWav);
        });

        GptSovitsClient client = new GptSovitsClient(
                config(Path.of("/tmp/closure-gpt.ckpt"), Path.of("/tmp/closure-sovits.pth")),
                java.net.http.HttpClient.newHttpClient(),
                warnings::add
        );
        Path outputPath = synthesizeToFile(client, ttsRequest("短句。"));

        assertArrayEquals(fakeWav, Files.readAllBytes(outputPath));
        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.getFirst().contains("\"text_split_method\":\"cut0\""));
        assertTrue(requestBodies.get(1).contains("\"text_split_method\":\"cut5\""));
        assertEquals(1, warnings.size());

        Files.deleteIfExists(outputPath);
    }

    @Test
    void synthesizeIncludesServerBodyWhenTtsReturnsBadRequest() throws Exception {
        startServer(exchange -> writeResponse(exchange, 400, "bad request from server".getBytes(StandardCharsets.UTF_8)));

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> client().synthesize(ttsRequest("错误测试")).get(3, TimeUnit.SECONDS)
        );

        assertTrue(exception.getCause().getMessage().contains("bad request from server"));
    }

    @Test
    void synthesizeFallsBackToGetWhenPostBodyIsRejectedAsMissing() throws Exception {
        byte[] fakeWav = "RIFF-fallback-WAVE".getBytes(StandardCharsets.US_ASCII);
        List<String> requestMethods = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> getQuery = new AtomicReference<>();

        startServer(exchange -> {
            requestMethods.add(exchange.getRequestMethod());
            if ("POST".equals(exchange.getRequestMethod())) {
                writeResponse(
                        exchange,
                        422,
                        "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\"],\"msg\":\"Field required\",\"input\":null}]}".getBytes(StandardCharsets.UTF_8)
                );
                return;
            }

            getQuery.set(exchange.getRequestURI().getRawQuery());
            writeResponse(exchange, 200, fakeWav);
        });

        Path outputPath = synthesizeToFile(client(), ttsRequest("博士，回退测试。"));

        assertArrayEquals(fakeWav, Files.readAllBytes(outputPath));
        assertEquals(List.of("POST", "GET"), requestMethods);
        assertTrue(getQuery.get().contains("text=" + URLEncoder.encode("博士，回退测试。", StandardCharsets.UTF_8)));
        assertTrue(getQuery.get().contains("media_type=wav"));
        assertTrue(getQuery.get().contains("seed=42"));
        assertTrue(getQuery.get().contains("repetition_penalty=1.35"));
        assertTrue(getQuery.get().contains("text_split_method=cut0"));

        Files.deleteIfExists(outputPath);
    }

    @Test
    void synthesizeRejectsEmptyWavBody() throws Exception {
        startServer(exchange -> writeResponse(exchange, 200, new byte[0]));

        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> client().synthesize(ttsRequest("空响应测试")).get(3, TimeUnit.SECONDS)
        );

        assertTrue(exception.getCause().getMessage().contains("空 WAV"));
    }

    @Test
    void initializeModelsUrlEncodesWeightPathsInOrder() throws Exception {
        List<String> calledPaths = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> gptQuery = new AtomicReference<>();
        AtomicReference<String> sovitsQuery = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/set_gpt_weights", exchange -> {
            calledPaths.add(exchange.getRequestURI().getPath());
            gptQuery.set(exchange.getRequestURI().getRawQuery());
            writeResponse(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/set_sovits_weights", exchange -> {
            calledPaths.add(exchange.getRequestURI().getPath());
            sovitsQuery.set(exchange.getRequestURI().getRawQuery());
            writeResponse(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        GptSovitsClient client = new GptSovitsClient(config(
                Path.of("/tmp/closure gpt/模型.ckpt"),
                Path.of("/tmp/closure sovits/模型.pth")
        ));

        client.initializeModels().get(3, TimeUnit.SECONDS);

        assertEquals(List.of("/set_gpt_weights", "/set_sovits_weights"), calledPaths);
        assertEquals(
                "weights_path=" + URLEncoder.encode("/tmp/closure gpt/模型.ckpt", StandardCharsets.UTF_8),
                gptQuery.get()
        );
        assertEquals(
                "weights_path=" + URLEncoder.encode("/tmp/closure sovits/模型.pth", StandardCharsets.UTF_8),
                sovitsQuery.get()
        );
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tts", handler::handle);
        server.start();
    }

    private GptSovitsClient client() {
        return new GptSovitsClient(config(
                Path.of("/tmp/closure-gpt.ckpt"),
                Path.of("/tmp/closure-sovits.pth")
        ));
    }

    private GptSovitsConfig config(Path gptWeightPath, Path sovitsWeightPath) {
        return new GptSovitsConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                gptWeightPath,
                sovitsWeightPath,
                voiceProfile(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3)
        );
    }

    private TtsRequest ttsRequest(String text) {
        return new TtsRequest(text, "zh", voiceProfile(), 1.0);
    }

    private VoiceProfile voiceProfile() {
        return new VoiceProfile(
                "neutral",
                Path.of("/tmp/neutral.wav"),
                "六号无人机起飞，视野正常……可以出发。",
                "zh"
        );
    }

    private Path synthesizeToFile(GptSovitsClient client, TtsRequest request) throws Exception {
        TtsAudioResult result = client.synthesize(request).get(3, TimeUnit.SECONDS);
        FileTtsAudioResult fileResult = assertInstanceOf(FileTtsAudioResult.class, result);
        return fileResult.getAudioFile();
    }

    private void writeResponse(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
