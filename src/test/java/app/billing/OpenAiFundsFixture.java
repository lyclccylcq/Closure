package app.billing;

import app.ai.AiModelConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test-only complete HTTP + runtime service fixture; all data and credentials are synthetic. */
public final class OpenAiFundsFixture implements AutoCloseable {
    private final HttpServer server;
    private volatile Instant now = Instant.parse("2026-11-28T10:30:00Z");
    private volatile String buckets;
    private volatile int status = 200;
    private volatile boolean incomplete;
    private volatile boolean storedReadFailure;
    private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    private final RuntimeBalanceService service;
    private final java.util.function.Function<String,BalanceService> adminClients;
    private final OpenAiBalanceStore funds;
    private final AdminKeyStore persistentKeys = new AdminKeyStore() {
        private volatile String value;
        public String load() {
            if (storedReadFailure) throw new BalanceException("系统钥匙串暂时无法访问，请解锁后重试。", BalanceException.Action.ACCESS_OPENAI_ADMIN);
            return value;
        }
        public boolean contains() { return value != null; }
        public void save(String key) { value = key; }
        public void clear() { value = null; }
        public boolean persistent() { return true; }
    };

    public OpenAiFundsFixture(Path file) throws Exception {
        setCost("1.234567");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("{\"object\":\"page\",\"data\":[" + buckets + "],\"has_more\":" + incomplete + ",\"next_page\":null}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient routed = new ApiBalanceClientTest.RoutedClient(HttpClient.newHttpClient(), request -> {
            requests.add(request);
            return HttpRequest.newBuilder(URI.create(origin + request.uri().getRawPath() + "?" + request.uri().getRawQuery()))
                    .header("Authorization", request.headers().firstValue("Authorization").orElseThrow()).GET().build();
        });
        Clock clock = new Clock() {
            public Instant instant() { return now; }
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
        };
        adminClients = key -> new OpenAiAdminClient(key, routed, clock);
        funds = new OpenAiBalanceStore(file);
        service = new RuntimeBalanceService(() -> new AiModelConfig(URI.create("https://api.openai.com/v1/chat/completions"),
                "synthetic-chat-key", "fixture-model", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                () -> "synthetic-admin-funds", adminClients,
                config -> { throw new AssertionError("No provider fallback expected"); }, funds);
    }
    public BalanceService service() { return service; }
    public BalanceService persistentService() {
        return persistentService(persistentKeys);
    }
    public BalanceService keychainService(String namespace) {
        testNamespace(namespace);
        return persistentService(new MacOsAdminKeyStore(namespace, "test-only"));
    }
    public static void clearSyntheticKeychain(String namespace) {
        testNamespace(namespace);
        try (var store = new MacOsAdminKeyStore(namespace, "test-only")) { store.clear(); }
    }
    private static void testNamespace(String value) {
        if (value == null || !value.matches("com\\.closure\\.tests\\.restart\\.[a-f0-9]{32}"))
            throw new IllegalArgumentException("Only unique synthetic restart namespaces are permitted");
    }
    private BalanceService persistentService(AdminKeyStore keys) {
        return new RuntimeBalanceService(() -> new AiModelConfig(URI.create("https://api.openai.com/v1/chat/completions"),
                "synthetic-chat-key", "fixture-model", Duration.ofSeconds(1), Duration.ofSeconds(1)), () -> null,
                adminClients, config -> { throw new AssertionError(); }, funds, keys);
    }
    public void setStoredReadFailure(boolean value) { storedReadFailure = value; }
    public void setNow(Instant value) { now = value; }
    public void setCost(String amount) { buckets = bucket(now.atZone(ZoneOffset.UTC).toLocalDate().toString(), amount, "usd"); }
    public void setBuckets(String value) { buckets = value; }
    public void setStatus(int value) { status = value; }
    public void setIncomplete(boolean value) { incomplete = value; }
    public List<HttpRequest> requests() { return List.copyOf(requests); }
    public static String bucket(String day, String amount, String currency) {
        long start = Instant.parse(day + "T00:00:00Z").getEpochSecond();
        return "{\"object\":\"bucket\",\"start_time\":" + start + ",\"end_time\":" + (start + 86400)
                + ",\"results\":[{\"object\":\"organization.costs.result\",\"amount\":{\"value\":" + amount + ",\"currency\":\"" + currency + "\"}}]}";
    }
    @Override public void close() { service.close(); server.stop(0); }
}
