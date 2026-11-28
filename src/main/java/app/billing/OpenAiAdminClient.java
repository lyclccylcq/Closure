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

    @Override public CompletableFuture<BalanceReport> query() { return querySince(null); }

    @Override public CompletableFuture<BalanceReport> querySince(Instant since) {
        Instant end = clock.instant();
        LocalDate today = end.atZone(ZoneOffset.UTC).toLocalDate();
        long month = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long day = today.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long start = since == null ? month : Math.min(month, since.getEpochSecond());
        if (since != null && (since.getNano() != 0 || Math.floorMod(since.getEpochSecond(), 86400) != 0)) {
            return CompletableFuture.failedFuture(new BalanceException("余额同步的统计起点无效，请重新同步。"));
        }
        // At the exact UTC month boundary there is no completed query interval yet.
        if (end.getEpochSecond() <= start) return CompletableFuture.completedFuture(report(List.of(), start, day, today, end));
        Query query = new Query(start, end.getEpochSecond());
        var costs = query.pages("costs", null, new HashSet<>(), new HashSet<>(), new ArrayList<>());
        CompletableFuture<BalanceReport> result = costs.thenApply(charges -> report(charges, start, day, today, end));
        result.orTimeout(25, TimeUnit.SECONDS).whenComplete((value, error) -> { if (error != null) query.cancel(); });
        return result;
    }

    private record Bucket(long start, List<?> results) { }

    private BalanceReport report(List<Bucket> costs, long start, long day, LocalDate today, Instant checked) {
        Map<String,BigDecimal> monthCosts = new LinkedHashMap<>(), dayCosts = new LinkedHashMap<>();
        long month = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        BigDecimal allUsd = BigDecimal.ZERO;
        boolean usdOnly = true;
        for (Bucket bucket : costs) {
            for (Object raw : bucket.results()) {
                Map<String,Object> item = BalanceJson.object(raw);
                if (!"organization.costs.result".equals(item.get("object"))) throw BalanceJson.invalid();
                Map<String,Object> amount = BalanceJson.object(item.get("amount"));
                String currency = BalanceJson.string(amount.get("currency")).toUpperCase(Locale.ROOT);
                if (!currency.matches("[A-Z]{3}")) throw BalanceJson.invalid();
                BigDecimal value = BalanceJson.number(amount.get("value"));
                if (currency.equals("USD")) allUsd = allUsd.add(value); else usdOnly = false;
                if (bucket.start() >= month) monthCosts.merge(currency, value, BigDecimal::add);
                if (bucket.start() >= day) dayCosts.merge(currency, value, BigDecimal::add);
                if (monthCosts.size() > 8) throw BalanceJson.invalid();
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
        return new BalanceReport("OpenAI · 今日消耗", lines,
                "费用按 UTC 日期与组织汇总，官方数据可能延迟。费用不等于充值余额。同步后的余额为估算。", checked,
                new BalanceReport.CostSnapshot(Instant.ofEpochSecond(start), checked, allUsd, usdOnly), null);
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
                if (status == 401 || status == 403) throw new BalanceException("OpenAI 未允许此管理密钥读取组织费用，请检查密钥有效性与组织权限后重试。",
                        BalanceException.Action.REVIEW_OPENAI_ADMIN);
                if (status == 429) throw new BalanceException("OpenAI 查询有点频繁，稍后再试一下吧。");
                if (status != 200) throw new BalanceException("OpenAI 费用与用量暂时没查到（HTTP " + status + "），请稍后重试。");
                Map<String,Object> json;
                try { json = BalanceJson.read(reply.body()); }
                catch (BalanceException invalid) {
                    throw new BalanceException("OpenAI " + (resource.equals("costs") ? "费用数据" : "模型用量数据")
                            + "暂时无法解析，请稍后重试。");
                }
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
