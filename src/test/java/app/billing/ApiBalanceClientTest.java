package app.billing;

import app.ai.AiModelConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ApiBalanceClientTest {
    private HttpServer server;
    private URI origin;
    private final Map<String,String> bodies = new ConcurrentHashMap<>();
    private final Map<String,Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String,String> auth = new ConcurrentHashMap<>();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-28T03:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); requests.add(path);
            auth.put(path, exchange.getRequestHeaders().getFirst("Authorization") == null ? "" : exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = bodies.getOrDefault(path, "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (statuses.getOrDefault(path, 0) == 302) exchange.getResponseHeaders().set("Location", origin + "/steal");
            exchange.sendResponseHeaders(statuses.getOrDefault(path, bodies.containsKey(path) ? 200 : 404), body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }
    private ApiBalanceClient client() { return new ApiBalanceClient(config(origin + "/v1/chat/completions"), HttpClient.newHttpClient(), CLOCK); }
    private static AiModelConfig config(String endpoint) { return new AiModelConfig(URI.create(endpoint), "synthetic-secret", "synthetic-model", Duration.ofSeconds(1), Duration.ofSeconds(2)); }
    private void token(String extra, boolean unlimited, String available) {
        bodies.put("/api/usage/token", "{\"code\":true,\"data\":{\"object\":\"token_usage\",\"unlimited_quota\":" + unlimited + ",\"total_available\":" + available + extra + "}}");
    }
    private void metadata(String extra) { bodies.put("/api/status", "{\"success\":true,\"data\":{\"quota_per_unit\":500000," + extra + "}}"); }

    @Test void currentOriginAndTokenAreUsedOnlyForTheReadOnlyQuery() throws Exception {
        token(",\"user_quota\":{\"total_available\":6172839}", false, "250000"); metadata("\"quota_display_type\":\"USD\"");
        BalanceReport report = client().query().get(3, TimeUnit.SECONDS);
        assertEquals(CLOCK.instant(), report.checkedAt());
        assertEquals(new BigDecimal("12.345678"), report.lines().getFirst().amount());
        assertEquals("账户", report.lines().getFirst().scope()); assertEquals("USD", report.lines().getFirst().unit());
        assertEquals(new BigDecimal("0.5"), report.lines().getLast().amount());
        assertEquals(List.of("/api/usage/token", "/api/status"), requests);
        assertEquals("Bearer synthetic-secret", auth.get("/api/usage/token")); assertEquals("", auth.get("/api/status"));
        assertFalse(report.toString().contains("synthetic-secret"));
    }

    @Test void unlimitedTokenDoesNotInventUnlimitedAccountFunds() {
        token("", true, "0");
        BalanceReport report = client().query().join();
        assertTrue(report.lines().getFirst().unlimited()); assertNull(report.lines().getFirst().amount());
        assertTrue(report.speechText().contains("账户余额")); assertFalse(report.speechText().contains("账户未设额度上限"));
    }

    @Test void unknownCurrencyKeepsExactQuotaInsteadOfAssumingDollars() {
        token("", false, "123456789");
        BalanceReport report = client().query().join();
        assertEquals("额度单位", report.lines().getFirst().unit());
        assertEquals(new BigDecimal("123456789"), report.lines().getFirst().amount());
        assertFalse(report.speechText().contains("美元"));
    }

    @Test void yuanDisplayUsesTheSitesExchangeRateAndCanRepresentAnOverdrawnAccount() {
        token(",\"user_quota\":{\"total_available\":-500000}", false, "500000");
        metadata("\"quota_display_type\":\"CNY\",\"usd_exchange_rate\":7.2");
        var report = client().query().join();
        assertEquals(new BigDecimal("-7.2"), report.lines().getFirst().amount()); assertEquals("CNY", report.lines().getFirst().unit());
        metadata("\"quota_display_type\":\"CNY\",\"usd_exchange_rate\":-7.2");
        assertEquals("额度单位", client().query().join().lines().getFirst().unit());
    }

    @Test void legacyBillingSubtractsUsageInHundredthsAndLabelsItsScope() {
        bodies.put("/dashboard/billing/subscription", "{\"object\":\"billing_subscription\",\"hard_limit_usd\":12.345678}");
        bodies.put("/dashboard/billing/usage", "{\"object\":\"list\",\"total_usage\":123.4567}");
        metadata("\"display_in_currency\":true");
        BalanceReport report = client().query().join();
        assertEquals(new BigDecimal("11.111111"), report.lines().getFirst().amount());
        assertEquals("接口可用额度", report.lines().getFirst().scope()); assertTrue(report.note().contains("口径"));
    }

    @Test void reverseProxyPathPrefixIsPreserved() {
        bodies.put("/gateway/api/usage/token", "{\"code\":true,\"data\":{\"object\":\"token_usage\",\"total_available\":123,\"unlimited_quota\":false}}");
        new ApiBalanceClient(config(origin + "/gateway/v1/chat/completions")).query().join();
        assertTrue(requests.contains("/gateway/api/usage/token")); assertFalse(requests.contains("/api/usage/token"));
    }

    @Test void errorsAndRedirectsNeverEchoTheProviderBodyOrForwardCredentials() {
        for (int status : new int[]{401, 403, 429, 500, 302}) {
            statuses.put("/api/usage/token", status); bodies.put("/api/usage/token", "synthetic-secret private-error");
            var error = assertThrows(java.util.concurrent.CompletionException.class, () -> client().query().join());
            assertTrue(error.getCause() instanceof BalanceException); assertFalse(error.toString().contains("synthetic-secret"));
        }
        assertFalse(requests.contains("/steal"));
    }

    @Test void incompleteOrHtmlSuccessNeverBecomesAZeroBalance() {
        for (String invalid : List.of("<html>login</html>", "{}", "{\"error\":\"denied\"}", "{\"code\":true,\"data\":{\"object\":\"token_usage\",\"unlimited_quota\":false}}")) {
            bodies.put("/api/usage/token", invalid);
            assertThrows(java.util.concurrent.CompletionException.class, () -> client().query().join());
        }
    }

    @Test void officialDeepSeekUsesItsOwnDocumentedEndpointAndAllCurrencyEntries() throws Exception {
        bodies.put("/user/balance", "{\"is_available\":false,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"0\"},{\"currency\":\"USD\",\"total_balance\":\"0.000001\"}]}");
        HttpClient delegate = HttpClient.newHttpClient();
        AtomicReference<URI> requested = new AtomicReference<>();
        HttpClient routed = new RoutedClient(delegate, request -> {
            requested.set(request.uri());
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + request.uri().getPath())).GET();
            request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            return builder.build();
        });
        BalanceReport report = new ApiBalanceClient(config("https://api.deepseek.com/v1/chat/completions"), routed, CLOCK).query().get(3, TimeUnit.SECONDS);
        assertEquals("https://api.deepseek.com/user/balance", requested.get().toString());
        assertEquals(2, report.lines().size()); assertEquals(new BigDecimal("0.000001"), report.lines().getLast().amount());
        assertTrue(report.note().contains("暂不可调用")); assertEquals("Bearer synthetic-secret", auth.get("/user/balance"));
    }

    @Test void cancellationRemainsCancellationAndUnsupportedDoesNotGuessOfficialBilling() {
        CompletableFuture<HttpResponse<String>> waiting = new CompletableFuture<>();
        HttpClient blocked = new RoutedClient(HttpClient.newHttpClient(), request -> request) {
            @Override @SuppressWarnings("unchecked") public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) waiting; }
        };
        CompletableFuture<BalanceReport> result = new ApiBalanceClient(config(origin + "/v1/chat/completions"), blocked, CLOCK).query();
        result.cancel(true); assertTrue(waiting.isCancelled());
        assertThrows(java.util.concurrent.CompletionException.class, () -> new ApiBalanceClient(config("https://api.openai.com/v1/chat/completions"), blocked, CLOCK).query().join());
        assertTrue(requests.isEmpty());
    }

    @Test void siliconFlowTotalIsReadWithoutInventingTheMissingCurrency() throws Exception {
        bodies.put("/v1/user/info", "{\"code\":20000,\"status\":true,\"data\":{\"balance\":\"0.01\",\"chargeBalance\":\"1.00\",\"totalBalance\":\"1.01\"}}");
        HttpClient routed = new RoutedClient(HttpClient.newHttpClient(), request -> HttpRequest.newBuilder(
                URI.create(origin + request.uri().getPath())).header("Authorization", request.headers().firstValue("Authorization").orElseThrow()).GET().build());
        BalanceReport report = new ApiBalanceClient(config("https://api.siliconflow.cn/v1/chat/completions"), routed, CLOCK).query().get(3, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("1.01"), report.lines().getFirst().amount());
        assertTrue(report.note().contains("没有注明币种")); assertFalse(report.speechText().contains("美元"));
    }

    @Test void aStalledProviderHasABoundedRequestAndCannotFabricateAResult() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpClient timedOut = new RoutedClient(HttpClient.newHttpClient(), request -> request) {
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                captured.set(request); return CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("synthetic timeout"));
            }
        };
        var future = new ApiBalanceClient(config(origin + "/v1/chat/completions"), timedOut, CLOCK).query();
        assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertTrue(captured.get().timeout().orElseThrow().compareTo(Duration.ofSeconds(8)) <= 0);
    }

    @Test void strictJsonPreservesDecimalsAndRejectsAmbiguity() {
        assertEquals(new BigDecimal("12.000001"), BalanceJson.read("{\"n\":12.000001}").get("n"));
        for (String invalid : List.of("{\"n\":1,\"n\":2}", "{\"n\":01}", "{\"n\":1}junk", "{\"n\":1e99999}", "{\"n\":NaN}", "{\"n\":null,\"n\":3}")) assertThrows(BalanceException.class, () -> BalanceJson.read(invalid));
    }

    static class RoutedClient extends HttpClient {
        private final HttpClient delegate;
        private final java.util.function.UnaryOperator<HttpRequest> route;
        RoutedClient(HttpClient delegate, java.util.function.UnaryOperator<HttpRequest> route) { this.delegate = delegate; this.route = route; }
        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        public java.util.Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public java.util.Optional<java.net.ProxySelector> proxy() { return delegate.proxy(); }
        public javax.net.ssl.SSLContext sslContext() { return delegate.sslContext(); }
        public javax.net.ssl.SSLParameters sslParameters() { return delegate.sslParameters(); }
        public java.util.Optional<java.net.Authenticator> authenticator() { return delegate.authenticator(); }
        public Version version() { return delegate.version(); }
        public java.util.Optional<java.util.concurrent.Executor> executor() { return delegate.executor(); }
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException { return delegate.send(route.apply(request), handler); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { return delegate.sendAsync(route.apply(request), handler); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { return delegate.sendAsync(route.apply(request), handler, push); }
    }
}
