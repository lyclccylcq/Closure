package app.voice;

import app.voice.model.FileTtsAudioResult;
import app.voice.model.TtsRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FishAudioClientTest {
    @TempDir Path directory;
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void readinessAndInitializationMakeNoHttpRequests() throws Exception {
        start(exchange -> respond(exchange, 200, wav()));
        FishAudioClient client = client();
        client.checkAvailable().get(1, TimeUnit.SECONDS);
        client.initializeModels().get(1, TimeUnit.SECONDS);
        assertFalse(client.supportsStreaming());
        assertEquals(0, calls.get());
        assertDirectoryEmpty();
    }

    @Test
    void wholeReplyUsesExplicitFreeModelAndOneFixedReferenceWithoutLocalProfileControls() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> model = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        start(exchange -> {
            method.set(exchange.getRequestMethod());
            model.set(exchange.getRequestHeaders().getFirst("model"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, wav());
        });
        Path file = ((FileTtsAudioResult) client().synthesize(request("博士，\"找到了\"？\n杯垫\\下面。"))
                .get(3, TimeUnit.SECONDS)).getAudioFile();
        assertArrayEquals(wav(), Files.readAllBytes(file));
        assertEquals("POST", method.get());
        assertEquals("s2.1-pro-free", model.get());
        assertEquals("Bearer synthetic-test-token", auth.get());
        assertEquals("{\"text\":\"[natural conversational delivery, steady tone] 博士，\\\"找到了\\\"？\\n杯垫\\\\下面。\","
                + "\"reference_id\":\"4632f51d7f524e6da1952135e498a6a3\",\"format\":\"wav\","
                + "\"sample_rate\":44100,\"latency\":\"normal\",\"temperature\":0.5,\"top_p\":0.7,"
                + "\"chunk_length\":300,\"min_chunk_length\":100,\"condition_on_previous_chunks\":true,"
                + "\"prosody\":{\"speed\":1.0}}", body.get());
        assertFalse(body.get().contains("ref_audio_path"));
        assertFalse(body.get().contains("seed"));
        assertEquals(1, calls.get());
        Files.delete(file);
    }

    @Test
    void explicitDramaChoiceUsesDramaHeaderAndTheSameVoice() throws Exception {
        AtomicReference<String> selected = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            selected.set(exchange.getRequestHeaders().getFirst("model"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, wav());
        });
        Properties settings = properties();
        settings.setProperty("fish.model", "drama-3");
        FishAudioConfig config = FishAudioConfig.fromProperties(settings);
        Path file = ((FileTtsAudioResult) new FishAudioClient(config, directory).synthesize(
                new TtsRequest("同一个音源。", "zh", config.getVoiceProfile()))
                .get(3, TimeUnit.SECONDS)).getAudioFile();
        assertEquals("drama-3", selected.get());
        assertEquals("{\"text\":\"同一个音源。\",\"reference_id\":\"4632f51d7f524e6da1952135e498a6a3\","
                + "\"format\":\"wav\",\"sample_rate\":44100,\"latency\":\"normal\","
                + "\"temperature\":0.7,\"top_p\":0.7,\"prosody\":{\"speed\":1.0}}", body.get());
        assertEquals(1, calls.get());
        Files.delete(file);
    }

    @Test
    void multiSentenceSpeechAtChatLimitStaysOneRequestWithOneInitialCue() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        start(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, wav());
        });
        String text = "找到它啦？在杯垫下。".repeat(12);
        assertEquals(120, text.codePointCount(0, text.length()));
        Path file = ((FileTtsAudioResult) client().synthesize(request(text))
                .get(3, TimeUnit.SECONDS)).getAudioFile();
        assertTrue(body.get().startsWith("{\"text\":\"[natural conversational delivery, steady tone] " + text + "\","));
        assertEquals(1, body.get().chars().filter(character -> character == '[').count());
        assertEquals(1, calls.get());
        Files.delete(file);
    }

    @Test
    void failuresAreRedactedAndNeverRetriedOrRedirected() throws Exception {
        AtomicInteger status = new AtomicInteger(401);
        start(exchange -> {
            assertEquals("s2.1-pro-free", exchange.getRequestHeaders().getFirst("model"));
            exchange.getResponseHeaders().set("Location", "/v1/tts");
            respond(exchange, status.get(), "synthetic-test-token private-test-text".getBytes(StandardCharsets.UTF_8));
        });
        FishAudioClient client = client();
        for (int code : new int[]{401, 402, 403, 404, 429, 500, 422, 302}) {
            status.set(code);
            Throwable error = assertThrows(ExecutionException.class,
                    () -> client.synthesize(request("private-test-text")).get(3, TimeUnit.SECONDS)).getCause();
            assertInstanceOf(VoiceSynthesisException.class, error);
            assertTrue(error.getMessage().contains("HTTP " + code));
            assertFalse(error.toString().contains("synthetic-test-token"));
            assertFalse(error.toString().contains("private-test-text"));
            assertNull(error.getCause());
        }
        assertEquals(8, calls.get());
        assertDirectoryEmpty();
    }

    @Test
    void invalidAndTruncatedOrUnsupportedWavNeverReachAConsumer() throws Exception {
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        start(exchange -> respond(exchange, 200, bytes.get()));
        byte[] badFormat = wav(); badFormat[20] = 3;
        byte[] stereo = wav(); stereo[22] = 2;
        byte[] oddData = wav(); oddData[40] = 7;
        byte[] noData = Arrays.copyOf(wav(), 44); noData[4] = 36; noData[40] = 0;
        for (byte[] invalid : new byte[][]{new byte[0], "not audio".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOf(wav(), 50), badFormat, stereo, oddData, noData}) {
            bytes.set(invalid);
            AtomicInteger deliveries = new AtomicInteger();
            assertThrows(ExecutionException.class, () -> client().synthesizeStream(request("测试"),
                    audio -> deliveries.incrementAndGet()).get(3, TimeUnit.SECONDS));
            assertEquals(0, deliveries.get());
        }
        assertDirectoryEmpty();
    }

    @Test
    void streamingWavLengthSentinelsAreRepairedOnlyAfterCompleteDownload() throws Exception {
        byte[] unknownLength = wav();
        Arrays.fill(unknownLength, 4, 8, (byte) 0xff);
        Arrays.fill(unknownLength, 40, 44, (byte) 0xff);
        start(exchange -> respond(exchange, 200, unknownLength));
        Path file = ((FileTtsAudioResult) client().synthesize(request("测试"))
                .get(3, TimeUnit.SECONDS)).getAudioFile();
        assertArrayEquals(wav(), Files.readAllBytes(file));
        Files.delete(file);
    }

    @Test
    void documentedFishPlaceholderWaitsForCleanHttpEndThenBecomesPlayableWav() throws Exception {
        byte[] placeholder = fishPlaceholderWav();
        CountDownLatch headerSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(placeholder, 0, 44);
                output.flush();
                headerSent.countDown();
                await(release);
                output.write(placeholder, 44, placeholder.length - 44);
            }
        });
        var pending = client().synthesize(request("第一句。第二句？"));
        try {
            assertTrue(headerSent.await(3, TimeUnit.SECONDS));
            assertFalse(pending.isDone(), "Header alone must not become a playable result");
        } finally { release.countDown(); }
        Path file = ((FileTtsAudioResult) pending.get(3, TimeUnit.SECONDS)).getAudioFile();
        assertArrayEquals(wav(), Files.readAllBytes(file));
        assertEquals(1, calls.get());
        Files.delete(file);
        assertDirectoryEmpty();
    }

    @Test
    void fishPlaceholdersDoNotExcuseEmptyOddOrInconsistentAudio() throws Exception {
        AtomicReference<byte[]> response = new AtomicReference<>();
        start(exchange -> respond(exchange, 200, response.get()));
        byte[] onlyRiff = wav();
        ByteBuffer.wrap(onlyRiff).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0xffff_ff24);
        byte[] onlyData = wav();
        ByteBuffer.wrap(onlyData).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0xffff_ff00);
        byte[] unsupported = fishPlaceholderWav(); unsupported[20] = 3;
        for (byte[] invalid : new byte[][]{Arrays.copyOf(fishPlaceholderWav(), 44),
                Arrays.copyOf(fishPlaceholderWav(), 51), onlyRiff, onlyData, unsupported}) {
            response.set(invalid);
            assertThrows(ExecutionException.class,
                    () -> client().synthesize(request("测试")).get(3, TimeUnit.SECONDS));
        }
        assertDirectoryEmpty();
    }

    @Test
    void fishPlaceholderStillRejectsTransportTruncationBeforeHttpEnd() throws Exception {
        // A raw peer closes the connection at a deterministic truncated EOF;
        // HttpServer's fixed-length response wrapper may keep it open instead.
        try (ServerSocket peer = new ServerSocket()) {
            peer.bind(new InetSocketAddress("127.0.0.1", 0));
            CompletableFuture<Void> served = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (var socket = peer.accept()) {
                    socket.setSoTimeout(3000);
                    var input = socket.getInputStream();
                    ByteArrayOutputStream header = new ByteArrayOutputStream();
                    int end = 0;
                    byte[] delimiter = {'\r', '\n', '\r', '\n'};
                    while (end < delimiter.length) {
                        int next = input.read();
                        if (next < 0) throw new IOException("Missing request headers");
                        header.write(next);
                        end = next == delimiter[end] ? end + 1 : (next == '\r' ? 1 : 0);
                    }
                    String headers = header.toString(StandardCharsets.US_ASCII);
                    var length = java.util.regex.Pattern.compile("(?im)^Content-Length: (\\d+)").matcher(headers);
                    if (!length.find()) throw new IOException("Missing request length");
                    input.readNBytes(Integer.parseInt(length.group(1)));
                    calls.incrementAndGet();
                    byte[] payload = fishPlaceholderWav();
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: "
                            + (payload.length + 4) + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.write(payload);
                    output.flush();
                    served.complete(null);
                } catch (Throwable failure) { served.completeExceptionally(failure); }
            });
            Properties settings = new Properties();
            settings.setProperty("fish.apiKey", "synthetic-test-token");
            settings.setProperty("fish.baseUrl", "http://127.0.0.1:" + peer.getLocalPort());
            FishAudioConfig config = FishAudioConfig.fromProperties(settings);
            AtomicInteger deliveries = new AtomicInteger();
            assertThrows(ExecutionException.class, () -> new FishAudioClient(config, directory).synthesizeStream(
                    new TtsRequest("测试", "zh", config.getVoiceProfile()), audio -> deliveries.incrementAndGet())
                    .get(3, TimeUnit.SECONDS));
            served.get(3, TimeUnit.SECONDS);
            assertEquals(0, deliveries.get());
            assertEquals(1, calls.get());
            assertDirectoryEmpty();
        }
    }

    @Test
    void excessiveDeclaredAndActuallyStreamedBodiesAreRejected() throws Exception {
        AtomicInteger mode = new AtomicInteger();
        start(exchange -> {
            if (mode.get() == 0) {
                exchange.sendResponseHeaders(200, FishAudioClient.MAX_WAV_BYTES + 1L);
                exchange.close();
            } else {
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    byte[] block = new byte[16 * 1024];
                    for (int index = 0; index <= FishAudioClient.MAX_WAV_BYTES / block.length; index++) output.write(block);
                } catch (IOException ignored) { }
            }
        });
        assertThrows(ExecutionException.class, () -> client().synthesize(request("测试")).get(5, TimeUnit.SECONDS));
        mode.set(1);
        Throwable failure = assertThrows(ExecutionException.class,
                () -> client().synthesize(request("测试")).get(5, TimeUnit.SECONDS)).getCause();
        assertTrue(failure.getMessage().contains("大小上限"));
        assertEquals(2, calls.get());
        assertDirectoryEmpty();
    }

    @Test
    void requestDeadlineIncludesBodyDownloadAndSuppressesPartialAudio() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(new byte[]{'R', 'I', 'F', 'F'});
                output.flush();
                await(release);
            }
        });
        Properties properties = properties(); properties.setProperty("fish.requestTimeoutSeconds", "1");
        try {
            Throwable failure = assertThrows(ExecutionException.class, () -> new FishAudioClient(
                    FishAudioConfig.fromProperties(properties), directory).synthesize(request("测试"))
                    .get(3, TimeUnit.SECONDS)).getCause();
            assertTrue(failure.getMessage().contains("超时"));
            assertEquals(1, calls.get());
            assertDirectoryEmpty();
        } finally { release.countDown(); }
    }

    @Test
    void cancellationBeforeHeadersSuppressesLateDeliveryAndFiles() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        start(exchange -> {
            received.countDown();
            await(release);
            try { respond(exchange, 200, wav()); }
            catch (IOException ignored) { }
            finally { finished.countDown(); }
        });
        AtomicInteger deliveries = new AtomicInteger();
        CompletableFuture<Void> future = client().synthesizeStream(request("测试"), audio -> deliveries.incrementAndGet());
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
        } finally { release.countDown(); }
        assertTrue(finished.await(3, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
        assertEquals(0, deliveries.get());
        assertDirectoryEmpty();
    }

    @Test
    void cancellationDuringBodyDownloadPreventsCompletedWavDelivery() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(wav(), 0, 44);
                output.flush();
                received.countDown();
                await(release);
                output.write(wav(), 44, 8);
            } catch (IOException ignored) { }
            finally { finished.countDown(); }
        });
        AtomicInteger deliveries = new AtomicInteger();
        CompletableFuture<Void> future = client().synthesizeStream(request("测试"), audio -> deliveries.incrementAndGet());
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
        } finally { release.countDown(); }
        assertTrue(finished.await(3, TimeUnit.SECONDS));
        assertEquals(0, deliveries.get());
        assertDirectoryEmpty();
    }

    @Test
    void consumerFailureCleansAnAlreadyCreatedTemporaryWav() throws Exception {
        start(exchange -> respond(exchange, 200, wav()));
        AtomicReference<Path> delivered = new AtomicReference<>();
        assertThrows(ExecutionException.class, () -> client().synthesizeStream(request("测试"), audio -> {
            Path file = ((FileTtsAudioResult) audio).getAudioFile();
            assertTrue(Files.isRegularFile(file));
            delivered.set(file);
            throw new IllegalStateException("synthetic consumer failure");
        }).get(3, TimeUnit.SECONDS));
        assertNotNull(delivered.get());
        assertFalse(Files.exists(delivered.get()));
        assertDirectoryEmpty();
    }

    @Test
    void excessiveTextIsRejectedWithoutAnyNetworkCall() throws Exception {
        start(exchange -> respond(exchange, 200, wav()));
        assertThrows(ExecutionException.class,
                () -> client().synthesize(request("字".repeat(2001))).get(1, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
    }

    private FishAudioClient client() { return new FishAudioClient(FishAudioConfig.fromProperties(properties()), directory); }
    private Properties properties() {
        Properties properties = new Properties();
        properties.setProperty("fish.apiKey", "synthetic-test-token");
        properties.setProperty("fish.baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        return properties;
    }
    private TtsRequest request(String text) {
        return new TtsRequest(text, "zh", FishAudioConfig.fromProperties(properties()).getVoiceProfile());
    }
    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            handler.handle(exchange);
        });
        server.start();
    }
    private static void respond(HttpExchange exchange, int status, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "audio/wav");
        exchange.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        try (var body = exchange.getResponseBody()) { body.write(data); }
    }
    private static byte[] wav() {
        return ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(44)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(44100).putInt(88200)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(8)
                .putShort((short) 50).putShort((short) -50).putShort((short) 100).putShort((short) -100).array();
    }
    private static byte[] fishPlaceholderWav() {
        byte[] wav = wav();
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, 0xffff_ff24).putInt(40, 0xffff_ff00);
        return wav;
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
    private void assertDirectoryEmpty() throws IOException {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
