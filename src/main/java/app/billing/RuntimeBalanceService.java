package app.billing;

import app.ai.AiModelConfig;
import java.net.URI;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** The admin credential is separate from the chat configuration and is never persisted here. */
final class RuntimeBalanceService implements BalanceService {
    static final String ADMIN_KEY_ENV = "CLOSURE_OPENAI_ADMIN_KEY";
    private final Supplier<AiModelConfig> connections;
    private final Supplier<String> environmentAdminKey;
    private final Function<String,BalanceService> admins;
    private final Function<AiModelConfig,BalanceService> providers;
    private final OpenAiBalanceStore funds;
    private String sessionAdminKey;
    private boolean environmentDisabled;
    private boolean closed;
    private long generation;
    private final Set<CompletableFuture<BalanceReport>> pending = ConcurrentHashMap.newKeySet();

    RuntimeBalanceService() {
        this(AiModelConfig::fromEnvironment, () -> System.getenv(ADMIN_KEY_ENV),
                OpenAiAdminClient::new, ApiBalanceClient::new, OpenAiBalanceStore.local());
    }

    RuntimeBalanceService(Supplier<AiModelConfig> connections, Supplier<String> environmentAdminKey,
                          Function<String,BalanceService> admins, Function<AiModelConfig,BalanceService> providers) {
        this(connections, environmentAdminKey, admins, providers, new OpenAiBalanceStore(null));
    }
    RuntimeBalanceService(Supplier<AiModelConfig> connections, Supplier<String> environmentAdminKey,
                          Function<String,BalanceService> admins, Function<AiModelConfig,BalanceService> providers,
                          OpenAiBalanceStore funds) {
        this.connections = connections; this.environmentAdminKey = environmentAdminKey;
        this.admins = admins; this.providers = providers;
        this.funds = funds;
    }

    @Override public boolean canConfigureOpenAiAdminKey() { return true; }

    @Override public synchronized void configureOpenAiAdminKey(String key) {
        if (closed) throw new BalanceException("账户查询已关闭。");
        sessionAdminKey = OpenAiAdminClient.normalizeKey(key);
        environmentDisabled = false;
        generation++; pending.forEach(query -> query.cancel(true));
    }

    @Override public synchronized void clearOpenAiAdminKey() {
        sessionAdminKey = null;
        environmentDisabled = true;
        generation++; pending.forEach(query -> query.cancel(true));
    }

    private synchronized String adminKey() {
        if (closed) throw new BalanceException("账户查询已关闭。");
        return OpenAiAdminClient.normalizeKey(sessionAdminKey != null ? sessionAdminKey
                : environmentDisabled ? null : environmentAdminKey.get());
    }

    @Override public CompletableFuture<BalanceReport> query() { return request(null); }

    @Override public boolean canSyncOpenAiBalance() { return true; }
    @Override public CompletableFuture<BalanceReport> syncOpenAiBalance(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.precision() > 18 || Math.abs((long) amount.scale()) > 6) {
            return CompletableFuture.failedFuture(new BalanceException("请填写官方账单中的当前美元余额，金额须为非负数字。"));
        }
        return request(amount);
    }
    @Override public synchronized void clearSyncedOpenAiBalance() {
        generation++; pending.forEach(query -> query.cancel(true));
        funds.clear(OpenAiBalanceStore.account(adminKey()));
    }

    private CompletableFuture<BalanceReport> request(BigDecimal calibration) {
        CompletableFuture<BalanceReport> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) {
                synchronized (RuntimeBalanceService.this) { return super.cancel(interrupt); }
            }
        };
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new BalanceException("账户查询已关闭。"));
            pending.add(result);
        }
        AtomicReference<CompletableFuture<BalanceReport>> active = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            try {
                if (result.isDone()) return;
                synchronized (this) { if (closed) throw new BalanceException("账户查询已关闭。"); }
                AiModelConfig connection = connections.get();
                boolean official = officialOpenAi(connection.getEndpoint());
                if (calibration != null && !official) throw new BalanceException("只有 OpenAI 官方连接可同步这份美元余额。");
                String identity;
                BalanceService client;
                long captured;
                synchronized (this) {
                    captured = generation;
                    String key = official ? adminKey() : null;
                    identity = official ? OpenAiBalanceStore.account(key) : null;
                    client = official ? admins.apply(key) : providers.apply(connection);
                }
                OpenAiBalanceStore.Entry prior = official && calibration == null ? funds.load(identity) : null;
                if (result.isDone()) return;
                CompletableFuture<BalanceReport> query = prior == null ? client.query() : client.querySince(prior.from());
                active.set(query);
                if (result.isDone()) query.cancel(true);
                query.whenComplete((value, error) -> {
                    if (error != null) { result.completeExceptionally(error); return; }
                    synchronized (this) {
                        if (result.isDone()) return;
                        if (closed || captured != generation) { result.cancel(true); return; }
                        try {
                            BalanceReport decorated = value;
                            if (calibration != null) {
                                var costs = value == null ? null : value.costs();
                                if (costs == null || !costs.usdOnly()) throw new BalanceException("尚未取得完整的美元费用，余额未同步。请稍后重试。");
                                var entry = new OpenAiBalanceStore.Entry(identity, calibration, costs.usdTotal(), costs.from(), costs.through());
                                funds.save(entry); decorated = value.withFunding(entry.estimate(costs));
                            } else if (prior != null && value != null) decorated = value.withFunding(prior.estimate(value.costs()));
                            result.complete(decorated);
                        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
                    }
                });
            } catch (RuntimeException error) { result.completeExceptionally(error); }
        });
        result.whenComplete((value, error) -> {
            pending.remove(result);
            if (result.isCancelled() && active.get() != null) active.get().cancel(true);
        });
        return result;
    }

    static boolean officialOpenAi(URI endpoint) {
        return "https".equalsIgnoreCase(endpoint.getScheme())
                && "api.openai.com".equalsIgnoreCase(endpoint.getHost())
                && (endpoint.getPort() == -1 || endpoint.getPort() == 443)
                && endpoint.getUserInfo() == null;
    }

    @Override public synchronized void close() {
        closed = true; clearOpenAiAdminKey(); pending.forEach(query -> query.cancel(true));
    }
}
