package app.billing;

import app.ai.AiModelConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Read-only, same-origin provider adapters. No token-prefix guessing or LLM access. */
public final class ApiBalanceClient implements BalanceService {
    private final AiModelConfig config;
    private final HttpClient http;
    private final Clock clock;
    private final String prefix;

    public ApiBalanceClient(AiModelConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    ApiBalanceClient(AiModelConfig config, HttpClient http, Clock clock) {
        this.config = config; this.http = http; this.clock = clock;
        URI endpoint = config.getEndpoint();
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))) {
            throw new BalanceException("当前 API 地址无法用于余额查询，请检查模型连接地址。");
        }
        String path = endpoint.getPath();
        if (path.endsWith("/v1/chat/completions")) prefix = path.substring(0, path.length() - 20);
        else if (path.endsWith("/chat/completions")) prefix = path.substring(0, path.length() - 17);
        else prefix = "";
    }

    @Override public CompletableFuture<BalanceReport> query() {
        if (!config.hasApiKey()) return CompletableFuture.failedFuture(new BalanceException("还没有配置当前对话 API 的 token，请先检查模型连接。"));
        Query query = new Query();
        String host = config.getEndpoint().getHost().toLowerCase(Locale.ROOT);
        CompletableFuture<BalanceReport> operation;
        if (host.equals("api.deepseek.com")) {
            operation = query.get("/user/balance", true).thenApply(this::deepSeek);
        } else if (host.equals("api.siliconflow.cn")) {
            operation = query.get("/v1/user/info", true).thenApply(this::siliconFlow);
        } else if (host.equals("api.openai.com")) {
            operation = CompletableFuture.failedFuture(unsupported());
        } else {
            operation = query.get("/api/usage/token", true).thenCompose(token -> {
                if (token != null) return query.metadata().thenApply(metadata -> tokenUsage(token, metadata));
                return query.get("/dashboard/billing/subscription", true).thenCompose(subscription -> {
                    if (subscription == null) return CompletableFuture.failedFuture(unsupported());
                    return query.get("/dashboard/billing/usage", true).thenCompose(usage ->
                            query.metadata().thenApply(metadata -> legacy(subscription, usage, metadata)));
                });
            });
        }
        CompletableFuture<BalanceReport> result = new CompletableFuture<>();
        operation.whenComplete((report, error) -> {
            if (error == null) result.complete(report);
            else result.completeExceptionally(error);
        });
        result.orTimeout(20, TimeUnit.SECONDS).whenComplete((report, error) -> {
            if (error != null) query.cancel();
        });
        return result;
    }

    private BalanceReport deepSeek(Map<String,Object> json) {
        if (json == null || !(json.get("balance_infos") instanceof List<?> balances) || balances.isEmpty() || balances.size() > 8) throw BalanceJson.invalid();
        List<BalanceReport.Line> lines = new ArrayList<>();
        for (Object value : balances) {
            Map<String,Object> item = BalanceJson.object(value);
            String currency = BalanceJson.string(item.get("currency"));
            if (!List.of("CNY", "USD").contains(currency)) throw BalanceJson.invalid();
            lines.add(new BalanceReport.Line("账户", BalanceJson.number(item.get("total_balance")), currency, false));
        }
        boolean available = BalanceJson.bool(json.get("is_available"));
        return report("DeepSeek", lines, available ? "" : "平台提示当前账户暂不可调用 API。");
    }

    private BalanceReport siliconFlow(Map<String,Object> json) {
        if (json == null || !Boolean.TRUE.equals(json.get("status"))) throw BalanceJson.invalid();
        BigDecimal total = BalanceJson.number(BalanceJson.object(json.get("data")).get("totalBalance"));
        return report("SiliconFlow", List.of(new BalanceReport.Line("账户", total, "平台余额单位", false)),
                "接口没有注明币种，金额单位请以平台控制台为准。");
    }

    private BalanceReport tokenUsage(Map<String,Object> json, Map<String,Object> metadata) {
        Map<String,Object> data = BalanceJson.object(json.get("data"));
        if (!"token_usage".equals(data.get("object")) || !Boolean.TRUE.equals(json.get("code"))) throw BalanceJson.invalid();
        List<BalanceReport.Line> lines = new ArrayList<>();
        if (data.get("user_quota") instanceof Map<?,?> account) {
            lines.add(quota("账户", BalanceJson.number(account.get("total_available")), metadata));
        }
        boolean unlimited = BalanceJson.bool(data.get("unlimited_quota"));
        lines.add(unlimited ? new BalanceReport.Line("令牌", null, "", true)
                : quota("令牌", BalanceJson.number(data.get("total_available")), metadata));
        String note = lines.size() == 1 ? "平台没有向此令牌提供账户余额，账户资金请到控制台确认。" : "";
        if (lines.stream().anyMatch(line -> line.unit().equals("额度单位"))) note += "平台未注明可确认的金额单位，以上额度不直接视作金额。";
        return report("New API · " + config.getEndpoint().getHost(), lines, note);
    }

    private BalanceReport.Line quota(String scope, BigDecimal raw, Map<String,Object> metadata) {
        String unit = unit(metadata);
        if (!unit.equals("额度单位")) {
            try {
                BigDecimal perUnit = BalanceJson.number(metadata.get("quota_per_unit"));
                if (perUnit.signum() <= 0) throw BalanceJson.invalid();
                BigDecimal amount = raw.divide(perUnit);
                if (unit.equals("CNY")) {
                    BigDecimal rate = BalanceJson.number(metadata.get("usd_exchange_rate"));
                    if (rate.signum() <= 0) throw BalanceJson.invalid();
                    amount = amount.multiply(rate);
                }
                return new BalanceReport.Line(scope, amount, unit, false);
            } catch (ArithmeticException | BalanceException invalidUnit) { /* Keep the exact raw quota. */ }
        }
        return new BalanceReport.Line(scope, raw, "额度单位", false);
    }

    private BalanceReport legacy(Map<String,Object> subscription, Map<String,Object> usage, Map<String,Object> metadata) {
        if (!"billing_subscription".equals(subscription.get("object")) || usage == null || !"list".equals(usage.get("object"))) throw BalanceJson.invalid();
        BigDecimal granted = BalanceJson.number(subscription.get("hard_limit_usd"));
        if (granted.compareTo(new BigDecimal("100000000")) == 0) throw new BalanceException("平台兼容接口未返回可确认的有限余额，请到控制台确认。");
        BigDecimal used = BalanceJson.number(usage.get("total_usage")).movePointLeft(2);
        return report("兼容额度接口 · " + config.getEndpoint().getHost(),
                List.of(new BalanceReport.Line("接口可用额度", granted.subtract(used), unit(metadata), false)),
                "平台可能返回账户或令牌额度，具体口径以控制台为准。"
                        + (unit(metadata).equals("额度单位") ? "接口未注明币种，以上不直接视作金额。" : ""));
    }

    private static String unit(Map<String,Object> metadata) {
        Object type = metadata.get("quota_display_type");
        if ("USD".equals(type) || (type == null && Boolean.TRUE.equals(metadata.get("display_in_currency")))) return "USD";
        if ("CNY".equals(type)) return "CNY";
        return "额度单位";
    }

    private BalanceReport report(String platform, List<BalanceReport.Line> lines, String note) {
        return new BalanceReport(platform, lines, note, clock.instant());
    }

    private URI uri(String path) {
        URI endpoint = config.getEndpoint();
        try { return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), prefix + path, null, null); }
        catch (URISyntaxException invalid) { throw new BalanceException("当前 API 地址无法用于余额查询。"); }
    }

    private final class Query {
        private final List<CompletableFuture<?>> pending = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        CompletableFuture<Map<String,Object>> get(String path, boolean authenticated) {
            if (cancelled) return CompletableFuture.failedFuture(new CancellationException());
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(7))
                    .header("Accept", "application/json").GET();
            if (authenticated) builder.header("Authorization", "Bearer " + config.getApiKey());
            CompletableFuture<HttpResponse<String>> request = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            pending.add(request);
            if (cancelled) request.cancel(true);
            return request.thenApply(response -> {
                int status = response.statusCode();
                if (status == 404 || status == 405) return null;
                if (status == 401 || status == 403) throw new BalanceException("平台没有允许此 token 查询余额，请检查 token 或账户权限。");
                if (status == 429) throw new BalanceException("平台查询有点频繁，稍后再试一下吧。");
                if (status != 200) throw new BalanceException("余额接口暂时没有连通（HTTP " + status + "），请稍后重试。");
                Map<String,Object> json = BalanceJson.read(response.body());
                if (json.containsKey("error") || Boolean.FALSE.equals(json.get("success"))) throw BalanceJson.invalid();
                return json;
            });
        }
        CompletableFuture<Map<String,Object>> metadata() {
            return get("/api/status", false).handle((value, error) -> {
                if (error != null || value == null || !(value.get("data") instanceof Map<?,?>)) return Map.of();
                return BalanceJson.object(value.get("data"));
            });
        }
        void cancel() { cancelled = true; pending.forEach(future -> future.cancel(true)); }
    }

    private static BalanceException unsupported() {
        return new BalanceException("当前平台没有开放已支持的余额接口，暂时没法直接查询。请到平台控制台确认。");
    }
}
