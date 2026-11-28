package app.billing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiAdminClientTest {
    private static final Instant NOW = Instant.parse("2026-11-28T10:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long MONTH = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
    private static final long DAY = Instant.parse("2026-11-28T00:00:00Z").getEpochSecond();
    private HttpServer server;
    private URI origin;
    private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<URI,String> response = uri -> page("", false, null);
    private volatile int status = 200;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String text = response.apply(exchange.getRequestURI());
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            if (status == 302) exchange.getResponseHeaders().set("Location", origin + "/steal");
            exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiAdminClient client() { return client(CLOCK); }
    private OpenAiAdminClient client(Clock clock) {
        var route = new ApiBalanceClientTest.RoutedClient(HttpClient.newHttpClient(), request -> {
            requests.add(request);
            return HttpRequest.newBuilder(URI.create(origin + request.uri().getRawPath() + "?" + request.uri().getRawQuery()))
                    .header("Authorization", request.headers().firstValue("Authorization").orElseThrow()).GET().build();
        });
        return new OpenAiAdminClient("synthetic-admin-key", route, clock);
    }

    @Test void fixedReadOnlyEndpointsUseOnlyTheAdminCredentialAndExactUtcWindow() throws Exception {
        response = uri -> uri.getPath().endsWith("costs")
                ? page(bucket(MONTH, cost("1.10", "usd")) + "," + bucket(DAY, cost("0.200000000000001", "usd")), false, null)
                : page(bucket(DAY, usage(200, 50, 3)), false, null);
        BalanceReport report = client().query().get(3, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("0.200000000000001"), report.lines().get(0).amount());
        assertEquals(new BigDecimal("1.300000000000001"), report.lines().get(1).amount());
        assertEquals("USD", report.lines().getFirst().unit()); assertTrue(report.isUsageReport());
        assertEquals(new BigDecimal("200"), report.lines().get(2).amount());
        assertEquals(new BigDecimal("50"), report.lines().get(3).amount());
        assertEquals(new BigDecimal("3"), report.lines().get(4).amount());
        assertFalse(report.speechText().contains("剩余")); assertTrue(report.note().contains("费用不等于充值余额"));
        assertEquals(NOW, report.checkedAt()); assertEquals(2, requests.size());
        for (HttpRequest request : requests) {
            assertEquals("GET", request.method()); assertEquals("https", request.uri().getScheme());
            assertEquals("api.openai.com", request.uri().getHost());
            assertEquals("Bearer synthetic-admin-key", request.headers().firstValue("Authorization").orElseThrow());
            assertFalse(request.uri().toString().contains("synthetic-admin-key"));
            String query = request.uri().getQuery();
            assertTrue(query.contains("start_time=" + MONTH)); assertTrue(query.contains("end_time=" + NOW.getEpochSecond()));
            assertTrue(query.contains("bucket_width=1d")); assertTrue(query.contains("limit=31"));
        }
    }

    @Test void everyPageAndCurrencyIsAggregatedWithoutDoubleOrCurrencyConversion() {
        response = uri -> {
            boolean second = uri.getRawQuery().contains("page=");
            if (uri.getPath().endsWith("costs")) return second
                    ? page(bucket(DAY, cost("0.2", "usd") + "," + cost("0.03", "eur")), false, null)
                    : page(bucket(MONTH, cost("0.1", "usd")), true, "cursor+/&=?");
            return second ? page(bucket(DAY, usage(2, 3, 1)), false, null)
                    : page(bucket(MONTH, usage(10, 20, 2)), true, "usage-next");
        };
        BalanceReport report = client().query().join();
        assertEquals(new BigDecimal("0.3"), report.lines().get(1).amount());
        assertEquals("EUR", report.lines().get(2).unit());
        assertEquals(new BigDecimal("12"), report.lines().get(4).amount());
        assertEquals(new BigDecimal("23"), report.lines().get(5).amount());
        assertEquals(4, requests.size());
        assertTrue(requests.stream().anyMatch(request -> URLDecoder.decode(request.uri().getRawQuery(), StandardCharsets.UTF_8).contains("page=cursor+/&=?")));
    }

    @Test void emptyDataDoesNotInventAMonetaryBalanceOrACurrency() {
        BalanceReport report = client().query().join();
        assertEquals("条", report.lines().getFirst().unit());
        assertFalse(report.speechText().contains("美元")); assertFalse(report.speechText().contains("剩余"));
        assertTrue(report.lines().getFirst().scope().contains("费用记录"));
    }

    @Test void preciseNumericAndStringCostsKeepAllDigitsIncludingScientificRefunds() {
        String precise = "0.123456789012345678901234567890123456789012345678901234567890123456789";
        String refund = "-1.2345678901234567890123456789e-40";
        response = uri -> uri.getPath().endsWith("costs")
                ? page(bucket(DAY, cost(precise, "usd") + "," + cost("\"" + refund + "\"", "usd")), false, null)
                : page(bucket(DAY, usage(100, 20, 2)), false, null);
        BalanceReport report = client().query().join();
        BigDecimal total = new BigDecimal(precise).add(new BigDecimal(refund));
        assertEquals(total, report.lines().get(0).amount());
        assertEquals(total, report.lines().get(1).amount());
        assertTrue(report.lines().getFirst().text().contains(total.stripTrailingZeros().toPlainString()));
        assertFalse(report.speechText().contains("剩余"));
    }

    @Test void optionalFineGrainedNumbersDoNotInvalidateAnOtherwiseCompleteReport() {
        response = uri -> uri.getPath().endsWith("costs")
                ? page(bucket(DAY, cost("1.25", "usd").replace("}}", "},\"quantity\":1e-100}")), false, null)
                : page(bucket(DAY, usage(5, 2, 1)), false, null);
        BalanceReport report = client().query().join();
        assertEquals(new BigDecimal("1.25"), report.lines().getFirst().amount());
        assertEquals(new BigDecimal("5"), report.lines().get(2).amount());
    }

    @Test void invalidJsonIdentifiesTheReaderWithoutExposingItsResponseBody() {
        for (String resource : List.of("costs", "usage/completions")) {
            response = uri -> uri.getPath().endsWith(resource) ? "private-response synthetic-admin-key" : page("", false, null);
            var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> client().query().join());
            String message = failure.getCause().getMessage();
            assertTrue(message.contains(resource.equals("costs") ? "OpenAI 费用数据" : "OpenAI 模型用量数据"));
            assertFalse(message.contains("private-response")); assertFalse(message.contains("synthetic-admin-key"));
        }
        for (String invalid : List.of("1e99999", "1e-99999", "{\"unexpected\":1}", "null")) {
            response = uri -> uri.getPath().endsWith("costs")
                    ? page(bucket(DAY, cost(invalid, "usd")), false, null) : page("", false, null);
            assertThrows(java.util.concurrent.CompletionException.class, () -> client().query().join());
        }
    }

    @Test void refundsRemainNegativeCostsAndMonthRolloverUsesTheNewMonth() {
        response = uri -> uri.getPath().endsWith("costs") ? page(bucket(DAY, cost("-0.12", "usd")), false, null) : page("", false, null);
        assertEquals(new BigDecimal("-0.12"), client().query().join().lines().getFirst().amount());
        requests.clear(); response = uri -> page("", false, null);
        client(Clock.fixed(Instant.parse("2026-12-01T01:00:00Z"), ZoneOffset.UTC)).query().join();
        long december = Instant.parse("2026-12-01T00:00:00Z").getEpochSecond();
        assertTrue(requests.stream().allMatch(request -> request.uri().getQuery().contains("start_time=" + december)));
        requests.clear(); client(Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)).query().join();
        assertTrue(requests.isEmpty());
    }

    @Test void repeatedCursorsOrBucketsAndPartialBadResultsCannotProduceATotal() {
        for (Function<URI,String> broken : List.<Function<URI,String>>of(
                uri -> page(bucket(MONTH, cost("1", "usd")), true, "same"),
                uri -> page(bucket(MONTH, cost("1", "usd")) + "," + bucket(MONTH, cost("2", "usd")), false, null),
                uri -> page(bucket(MONTH - 1, cost("1", "usd")), false, null),
                uri -> page(bucket(MONTH, "{\"object\":\"organization.costs.result\"}"), false, null),
                uri -> "{\"object\":\"page\",\"data\":[],\"has_more\":true,\"next_page\":null}")) {
            response = uri -> uri.getPath().endsWith("costs") ? broken.apply(uri) : page("", false, null);
            assertThrows(java.util.concurrent.CompletionException.class, () -> client().query().join());
        }
    }

    @Test void missingKeysPermissionsErrorsRateLimitsAndRedirectsNeverExposeTheSecret() {
        BalanceException missing = assertThrows(BalanceException.class, () -> new OpenAiAdminClient(""));
        assertEquals(BalanceException.Action.CONFIGURE_OPENAI_ADMIN, missing.action());
        for (int code : new int[]{401, 403, 429, 500, 302}) {
            status = code; response = uri -> "synthetic-admin-key private-response-body";
            var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> client().query().join());
            assertTrue(failure.getCause() instanceof BalanceException); assertFalse(failure.toString().contains("synthetic-admin-key"));
            if (code == 401 || code == 403) assertEquals(BalanceException.Action.CONFIGURE_OPENAI_ADMIN, ((BalanceException) failure.getCause()).action());
        }
        assertTrue(requests.stream().noneMatch(request -> request.uri().getPath().contains("steal")));
    }

    @Test void cancellationStopsBothReadsAndTimeoutIsNotReportedAsZero() {
        List<CompletableFuture<?>> waiting = new CopyOnWriteArrayList<>();
        HttpClient blocked = new ApiBalanceClientTest.RoutedClient(HttpClient.newHttpClient(), request -> request) {
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                assertTrue(request.timeout().orElseThrow().toSeconds() <= 7);
                var future = new CompletableFuture<HttpResponse<T>>(); waiting.add(future); return future;
            }
        };
        CompletableFuture<BalanceReport> query = new OpenAiAdminClient("synthetic-admin-key", blocked, CLOCK).query();
        query.cancel(true); assertEquals(2, waiting.size()); assertTrue(waiting.stream().allMatch(CompletableFuture::isCancelled));
        HttpClient timeout = new ApiBalanceClientTest.RoutedClient(HttpClient.newHttpClient(), request -> request) {
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                return CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("synthetic timeout"));
            }
        };
        assertThrows(java.util.concurrent.CompletionException.class, () -> new OpenAiAdminClient("synthetic-admin-key", timeout, CLOCK).query().join());
    }

    private static String page(String buckets, boolean more, String next) {
        return "{\"object\":\"page\",\"data\":[" + buckets + "],\"has_more\":" + more + ",\"next_page\":" + (next == null ? "null" : "\"" + next + "\"") + "}";
    }
    private static String bucket(long start, String rows) { return "{\"object\":\"bucket\",\"start_time\":" + start + ",\"results\":[" + rows + "]}"; }
    private static String cost(String amount, String currency) { return "{\"object\":\"organization.costs.result\",\"amount\":{\"value\":" + amount + ",\"currency\":\"" + currency + "\"}}"; }
    private static String usage(int input, int output, int requests) { return "{\"object\":\"organization.usage.completions.result\",\"input_tokens\":" + input + ",\"output_tokens\":" + output + ",\"num_model_requests\":" + requests + "}"; }
}
