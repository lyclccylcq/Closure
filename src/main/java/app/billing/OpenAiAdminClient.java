package app.billing;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** OpenAI organization reads only. The destination is fixed and redirects are never followed. */
public final class OpenAiAdminClient implements BalanceService {
    private static final String ORIGIN = "https://api.openai.com/v1/organization/";
    private final String adminKey;
    private final HttpClient http;
    private final Clock clock;

    public OpenAiAdminClient(String adminKey) {
        this(adminKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    OpenAiAdminClient(String adminKey, HttpClient http, Clock clock) {
        this.adminKey = normalizeKey(adminKey); this.http = http; this.clock = clock;
    }

    static String normalizeKey(String key) {
        if (key == null || key.isBlank()) throw configuration("请先配置 OpenAI Admin API key，才能查询组织费用和用量。");
        String normalized = key.trim();
        if (normalized.length() < 8 || normalized.length() > 4096 || normalized.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw configuration("管理密钥格式无效，请重新粘贴完整的 Admin API key。");
        }
        return normalized;
    }

    @Override public CompletableFuture<BalanceReport> query() {
        Instant end = clock.instant();
        LocalDate today = end.atZone(ZoneOffset.UTC).toLocalDate();
        long month = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long day = today.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        // At the exact UTC month boundary there is no completed query interval yet.
        if (end.getEpochSecond() <= month) return CompletableFuture.completedFuture(report(List.of(), List.of(), day, today, end));
        Query query = new Query(month, end.getEpochSecond());
        CompletableFuture<BalanceReport> result = new CompletableFuture<>();
        var costs = query.pages("costs", null, new HashSet<>(), new HashSet<>(), new ArrayList<>());
        var usage = query.pages("usage/completions", null, new HashSet<>(), new HashSet<>(), new ArrayList<>());
        costs.whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); });
        usage.whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); });
        costs.thenCombine(usage, (charges, tokens) -> report(charges, tokens, day, today, end))
                .whenComplete((value, error) -> {
                    if (error == null) result.complete(value); else result.completeExceptionally(error);
                });
        result.orTimeout(25, TimeUnit.SECONDS).whenComplete((value, error) -> { if (error != null) query.cancel(); });
        return result;
    }

    private record Bucket(long start, List<?> results) { }

    private BalanceReport report(List<Bucket> costs, List<Bucket> usage, long day, LocalDate today, Instant checked) {
        Map<String,BigDecimal> monthCosts = new LinkedHashMap<>(), dayCosts = new LinkedHashMap<>();
        for (Bucket bucket : costs) {
            for (Object raw : bucket.results()) {
                Map<String,Object> item = BalanceJson.object(raw);
                if (!"organization.costs.result".equals(item.get("object"))) throw BalanceJson.invalid();
                Map<String,Object> amount = BalanceJson.object(item.get("amount"));
                String currency = BalanceJson.string(amount.get("currency")).toUpperCase(Locale.ROOT);
                if (!currency.matches("[A-Z]{3}")) throw BalanceJson.invalid();
                BigDecimal value = BalanceJson.number(amount.get("value"));
                monthCosts.merge(currency, value, BigDecimal::add);
                if (bucket.start() >= day) dayCosts.merge(currency, value, BigDecimal::add);
                if (monthCosts.size() > 8) throw BalanceJson.invalid();
            }
        }
        BigDecimal input = BigDecimal.ZERO, output = BigDecimal.ZERO, requests = BigDecimal.ZERO;
        for (Bucket bucket : usage) {
            for (Object raw : bucket.results()) {
                Map<String,Object> item = BalanceJson.object(raw);
                if (!"organization.usage.completions.result".equals(item.get("object"))) throw BalanceJson.invalid();
                input = input.add(count(item.get("input_tokens")));
                output = output.add(count(item.get("output_tokens")));
                requests = requests.add(count(item.get("num_model_requests")));
            }
        }
        List<BalanceReport.Line> lines = new ArrayList<>();
        if (monthCosts.isEmpty()) {
            lines.add(new BalanceReport.Line("今日费用记录（UTC）", BigDecimal.ZERO, "条", false, BalanceReport.Kind.USAGE));
            lines.add(new BalanceReport.Line("本月费用记录（UTC）", BigDecimal.ZERO, "条", false, BalanceReport.Kind.USAGE));
        } else monthCosts.forEach((currency, value) -> {
            lines.add(new BalanceReport.Line("今日费用（UTC）", dayCosts.getOrDefault(currency, BigDecimal.ZERO), currency, false, BalanceReport.Kind.COST));
            lines.add(new BalanceReport.Line("本月费用（UTC）", value, currency, false, BalanceReport.Kind.COST));
        });
        lines.add(new BalanceReport.Line("本月输入", input, "tokens", false, BalanceReport.Kind.USAGE));
        lines.add(new BalanceReport.Line("本月输出", output, "tokens", false, BalanceReport.Kind.USAGE));
        lines.add(new BalanceReport.Line("本月请求", requests, "次", false, BalanceReport.Kind.USAGE));
        return new BalanceReport("OpenAI · 组织费用与用量", lines,
                "统计区间 " + today.withDayOfMonth(1) + " 至 " + today + "（UTC）。组织汇总包含其他应用，官方数据可能延迟。费用不等于充值余额。", checked);
    }

    private static BigDecimal count(Object value) {
        BigDecimal number = BalanceJson.number(value);
        if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0) throw BalanceJson.invalid();
        return number;
    }

    private final class Query {
        private final long start, end;
        private final List<CompletableFuture<?>> pending = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        private Query(long start, long end) { this.start = start; this.end = end; }

        CompletableFuture<List<Bucket>> pages(String resource, String page, Set<String> cursors,
                                               Set<Long> starts, List<Bucket> collected) {
            if (cancelled) return CompletableFuture.failedFuture(new CancellationException());
            if (page != null && (!cursors.add(page) || cursors.size() > 64)) return CompletableFuture.failedFuture(BalanceJson.invalid());
            String path = ORIGIN + resource + "?start_time=" + start + "&end_time=" + end + "&bucket_width=1d&limit=31"
                    + (page == null ? "" : "&page=" + URLEncoder.encode(page, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(7))
                    .header("Authorization", "Bearer " + adminKey).header("Accept", "application/json").GET().build();
            CompletableFuture<HttpResponse<String>> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            pending.add(response); if (cancelled) response.cancel(true);
            return response.thenCompose(reply -> {
                int status = reply.statusCode();
                if (status == 401 || status == 403) throw configuration("OpenAI 未允许此管理密钥读取费用和用量，请检查密钥与组织权限。");
                if (status == 429) throw new BalanceException("OpenAI 查询有点频繁，稍后再试一下吧。");
                if (status != 200) throw new BalanceException("OpenAI 费用与用量暂时没查到（HTTP " + status + "），请稍后重试。");
                Map<String,Object> json = BalanceJson.read(reply.body());
                if (!"page".equals(json.get("object")) || !(json.get("data") instanceof List<?> buckets)) throw BalanceJson.invalid();
                for (Object raw : buckets) {
                    Map<String,Object> bucket = BalanceJson.object(raw);
                    long time;
                    try { time = BalanceJson.number(bucket.get("start_time")).longValueExact(); }
                    catch (ArithmeticException invalid) { throw BalanceJson.invalid(); }
                    if (!"bucket".equals(bucket.get("object")) || time < start || time >= end || !starts.add(time)
                            || !(bucket.get("results") instanceof List<?> rows)) throw BalanceJson.invalid();
                    collected.add(new Bucket(time, rows));
                }
                if (!BalanceJson.bool(json.get("has_more"))) return CompletableFuture.completedFuture(List.copyOf(collected));
                String next = BalanceJson.string(json.get("next_page"));
                if (next.isBlank() || next.length() > 1024) throw BalanceJson.invalid();
                return pages(resource, next, cursors, starts, collected);
            });
        }
        void cancel() { cancelled = true; pending.forEach(future -> future.cancel(true)); }
    }

    private static BalanceException configuration(String message) {
        return new BalanceException(message, BalanceException.Action.CONFIGURE_OPENAI_ADMIN);
    }
}
