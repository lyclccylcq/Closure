package app.voice;

import app.voice.model.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GptSovitsStreamingTest {
    private HttpServer server;
    private ExecutorService executor;
    private final List<Path> files = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws IOException {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        for (Path file : files) Files.deleteIfExists(file);
    }

    @Test
    void deliversFirstCompleteWavBeforeTheRestOfTheResponse() throws Exception {
        CountDownLatch receivedFirst = new CountDownLatch(1);
        CountDownLatch sendRest = new CountDownLatch(1);
        AtomicReference<String> request = new AtomicReference<>();
        start(exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream out = new DataOutputStream(exchange.getResponseBody())) {
                out.write("CTTS1\n".getBytes(StandardCharsets.US_ASCII));
                frame(out, wav(1)); out.flush();
                await(sendRest);
                frame(out, wav(2)); out.writeInt(0);
            }
        });
        var result = client(Duration.ofSeconds(5)).synthesizeStream(request(), audio -> {
            files.add(((FileTtsAudioResult) audio).getAudioFile());
            receivedFirst.countDown();
        });
        try {
            assertTrue(receivedFirst.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "First fragment must arrive before stream EOF");
            assertArrayEquals(wav(1), Files.readAllBytes(files.getFirst()));
        } finally {
            sendRest.countDown();
        }
        result.get(3, TimeUnit.SECONDS);
        assertEquals(2, files.size());
        assertArrayEquals(wav(2), Files.readAllBytes(files.get(1)));
        assertTrue(request.get().contains("\"text_split_method\":\"cut5\""));
    }

    @Test
    void reportsAnErrorDeliveredAfterStreamingHeaders() throws Exception {
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream out = new DataOutputStream(exchange.getResponseBody())) {
                out.write("CTTS1\n".getBytes(StandardCharsets.US_ASCII));
                byte[] error = "reference audio failed".getBytes(StandardCharsets.UTF_8);
                out.writeInt(-1); out.writeInt(error.length); out.write(error);
            }
        });
        var error = assertThrows(ExecutionException.class,
                () -> client(Duration.ofSeconds(5)).synthesizeStream(request(), audio -> fail()).get(3, TimeUnit.SECONDS));
        assertTrue(root(error).getMessage().contains("reference audio failed"));
    }

    @Test
    void rejectsAnEmptySuccessfulStream() throws Exception {
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream out = new DataOutputStream(exchange.getResponseBody())) {
                out.write("CTTS1\n".getBytes(StandardCharsets.US_ASCII));
                out.writeInt(0);
            }
        });
        var error = assertThrows(ExecutionException.class,
                () -> client(Duration.ofSeconds(5)).synthesizeStream(request(), audio -> fail()).get(3, TimeUnit.SECONDS));
        assertTrue(root(error).getMessage().contains("空语音流"));
    }

    @Test
    void rejectsTruncationInsteadOfReportingSuccessfulSpeech() throws Exception {
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream out = new DataOutputStream(exchange.getResponseBody())) {
                out.write("CTTS1\n".getBytes(StandardCharsets.US_ASCII));
                out.writeInt(100); out.write(wav(1));
            }
        });
        assertThrows(ExecutionException.class,
                () -> client(Duration.ofSeconds(5)).synthesizeStream(request(), audio -> fail()).get(3, TimeUnit.SECONDS));
    }

    @Test
    void cancellationDiscardsSubsequentAudioAndClosesTheResponse() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch rest = new CountDownLatch(1);
        CountDownLatch serverFinished = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream out = new DataOutputStream(exchange.getResponseBody())) {
                out.write("CTTS1\n".getBytes(StandardCharsets.US_ASCII));
                frame(out, wav(1)); out.flush();
                await(rest);
                frame(out, wav(2)); out.writeInt(0);
            } catch (IOException expectedOnDisconnect) {
                // Closing the client body can interrupt the server's remaining write.
            } finally {
                serverFinished.countDown();
            }
        });
        var result = client(Duration.ofSeconds(5)).synthesizeStream(request(), audio -> {
            files.add(((FileTtsAudioResult) audio).getAudioFile()); first.countDown();
        });
        try {
            assertTrue(first.await(3, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
        } finally {
            rest.countDown();
        }
        assertTrue(serverFinished.await(3, TimeUnit.SECONDS));
        assertTrue(result.isCancelled());
        assertEquals(1, files.size());
    }

    @Test
    void requestTimeoutAlsoCoversAStalledStreamingBody() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream out = new DataOutputStream(exchange.getResponseBody())) {
                out.write("CTTS1\n".getBytes(StandardCharsets.US_ASCII));
                frame(out, wav(1)); out.flush(); await(release);
            }
        });
        var result = client(Duration.ofMillis(600)).synthesizeStream(request(), audio -> {
            files.add(((FileTtsAudioResult) audio).getAudioFile()); first.countDown();
        });
        try {
            assertTrue(first.await(3, TimeUnit.SECONDS));
            var error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, root(error));
        } finally {
            release.countDown();
        }
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/closure/tts", handler::handle);
        server.start();
    }

    private GptSovitsClient client(Duration timeout) {
        var config = new GptSovitsConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Path.of("/srv/model.ckpt"), Path.of("/srv/model.pth"),
                Map.of(SpeechEmotion.NEUTRAL, profile()), Duration.ofSeconds(1), timeout,
                true, 80, "cut0", "cut5", 0.12, 42, true, true, 1, true, true);
        return new GptSovitsClient(config);
    }

    private VoiceProfile profile() {
        return new VoiceProfile("neutral", Path.of("/srv/neutral.wav"), "参考文本。", "zh");
    }

    private TtsRequest request() { return new TtsRequest("博士，第一句。第二句。", "zh", profile(), 0.95); }

    private static byte[] wav(int sample) {
        ByteBuffer b = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        b.putShort((short) 1).putShort((short) 1).putInt(32000).putInt(64000);
        b.putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short) sample);
        return b.array();
    }

    private static void frame(DataOutputStream stream, byte[] wav) throws IOException {
        stream.writeInt(wav.length); stream.write(wav);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(4, TimeUnit.SECONDS)) throw new IOException("Test synchronization timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException(error);
        }
    }

    private static Throwable root(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }

    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
