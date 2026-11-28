package app.billing;

import app.ai.AiModelConfig;
import java.net.URI;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Admin credentials remain separate from chat settings and local balance calibration. */
final class RuntimeBalanceService implements BalanceService {
    static final String ADMIN_KEY_ENV = "CLOSURE_OPENAI_ADMIN_KEY";
    private final Supplier<AiModelConfig> connections;
    private final Supplier<String> environmentAdminKey;
    private final Function<String,BalanceService> admins;
    private final Function<AiModelConfig,BalanceService> providers;
    private final OpenAiBalanceStore funds;
    private final AdminKeyStore keys;
    private final Object credentialLock = new Object();
    private final ExecutorService credentialExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "closure-admin-key-store"); thread.setDaemon(true); return thread;
    });
    private final Set<CompletableFuture<?>> credentialTasks = ConcurrentHashMap.newKeySet();
    private String sessionAdminKey;
    private boolean environmentDisabled;
    private boolean closed;
    private boolean credentialsChanging;
    private long generation;
    private final Set<CompletableFuture<BalanceReport>> pending = ConcurrentHashMap.newKeySet();

    RuntimeBalanceService() {
        this(AiModelConfig::fromEnvironment, () -> System.getenv(ADMIN_KEY_ENV),
                OpenAiAdminClient::new, ApiBalanceClient::new, OpenAiBalanceStore.local(), AdminKeyStore.systemDefault());
    }

    RuntimeBalanceService(Supplier<AiModelConfig> connections, Supplier<String> environmentAdminKey,
                          Function<String,BalanceService> admins, Function<AiModelConfig,BalanceService> providers) {
        this(connections, environmentAdminKey, admins, providers, new OpenAiBalanceStore(null));
    }
    RuntimeBalanceService(Supplier<AiModelConfig> connections, Supplier<String> environmentAdminKey,
                          Function<String,BalanceService> admins, Function<AiModelConfig,BalanceService> providers,
                          OpenAiBalanceStore funds) {
        this(connections, environmentAdminKey, admins, providers, funds, AdminKeyStore.memory());
    }
    RuntimeBalanceService(Supplier<AiModelConfig> connections, Supplier<String> environmentAdminKey,
                          Function<String,BalanceService> admins, Function<AiModelConfig,BalanceService> providers,
                          OpenAiBalanceStore funds, AdminKeyStore keys) {
        this.connections = connections; this.environmentAdminKey = environmentAdminKey;
        this.admins = admins; this.providers = providers;
        this.funds = funds;
        this.keys = keys;
    }

    @Override public boolean canConfigureOpenAiAdminKey() { return true; }
    @Override public boolean persistsOpenAiAdminKey() { return keys.persistent(); }
    @Override public CompletableFuture<AdminKeyState> openAiAdminKeyStateAsync() {
        return credentialTask(() -> {
            if (!officialOpenAi(connections.get().getEndpoint())) return AdminKeyState.UNKNOWN;
            synchronized (credentialLock) {
                synchronized (this) { if (closed) throw new BalanceException("账户查询已关闭。"); }
                boolean saved = keys.contains();
                synchronized (this) {
                    if (closed) throw new BalanceException("账户查询已关闭。");
                    if (saved) return keys.persistent() ? AdminKeyState.SAVED : AdminKeyState.SESSION;
                    String environment = environmentDisabled ? null : environmentAdminKey.get();
                    return sessionAdminKey != null || (environment != null && !environment.isBlank())
                            ? AdminKeyState.SESSION : AdminKeyState.MISSING;
                }
            }
        });
    }

    @Override public void configureOpenAiAdminKey(String key) {
        String normalized = OpenAiAdminClient.normalizeKey(key);
        synchronized (credentialLock) {
            long change = beginCredentialChange();
            try {
                keys.save(normalized);
                synchronized (this) {
                    if (!closed && generation == change) { sessionAdminKey = normalized; environmentDisabled = false; }
                }
            } finally { endCredentialChange(change); }
        }
    }

    @Override public void clearOpenAiAdminKey() {
        synchronized (credentialLock) {
            long change = beginCredentialChange();
            try {
                keys.clear();
                synchronized (this) {
                    if (!closed && generation == change) { sessionAdminKey = null; environmentDisabled = true; }
                }
            } finally { endCredentialChange(change); }
        }
    }

    private synchronized long beginCredentialChange() {
        if (closed) throw new BalanceException("账户查询已关闭。");
        credentialsChanging = true; generation++; pending.forEach(query -> query.cancel(true)); return generation;
    }
    private synchronized void endCredentialChange(long change) { if (generation == change) credentialsChanging = false; }
    private synchronized void checkCredentialState(long expected) {
        if (closed) throw new BalanceException("账户查询已关闭。");
        if (generation != expected) throw new CancellationException();
        if (credentialsChanging) throw new BalanceException("管理密钥正在更新，请稍后重试。");
    }
    private String adminKey(long expected) {
        synchronized (this) {
            checkCredentialState(expected);
            if (sessionAdminKey != null) return sessionAdminKey;
            if (environmentDisabled) return OpenAiAdminClient.normalizeKey(null);
        }
        synchronized (credentialLock) {
            synchronized (this) {
                checkCredentialState(expected);
                if (sessionAdminKey != null) return sessionAdminKey;
                if (environmentDisabled) return OpenAiAdminClient.normalizeKey(null);
            }
            String saved = keys.load();
            String resolved = OpenAiAdminClient.normalizeKey(saved != null ? saved : environmentAdminKey.get());
            synchronized (this) { checkCredentialState(expected); sessionAdminKey = resolved; }
            return resolved;
        }
    }
    @Override public CompletableFuture<Void> configureOpenAiAdminKeyAsync(String key) {
        return credentialTask(() -> { configureOpenAiAdminKey(key); return null; });
    }
    @Override public CompletableFuture<Void> clearOpenAiAdminKeyAsync() {
        return credentialTask(() -> { clearOpenAiAdminKey(); return null; });
    }
    private synchronized <T> CompletableFuture<T> credentialTask(Supplier<T> action) {
        if (closed) return CompletableFuture.failedFuture(new BalanceException("账户查询已关闭。"));
        try {
            CompletableFuture<T> task = CompletableFuture.supplyAsync(action, credentialExecutor);
            credentialTasks.add(task); task.whenComplete((value, error) -> credentialTasks.remove(task)); return task;
        } catch (RejectedExecutionException unavailable) { return CompletableFuture.failedFuture(new BalanceException("账户查询已关闭。")); }
    }

    @Override public CompletableFuture<BalanceReport> query() { return request(null); }

    @Override public boolean canSyncOpenAiBalance() { return true; }
    @Override public CompletableFuture<BalanceReport> syncOpenAiBalance(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.precision() > 18 || Math.abs((long) amount.scale()) > 6) {
            return CompletableFuture.failedFuture(new BalanceException("请填写官方账单中的当前美元余额，金额须为非负数字。"));
        }
        return request(amount);
    }
    @Override public void clearSyncedOpenAiBalance() {
        long expected;
        synchronized (this) { expected = generation; }
        String key = adminKey(expected);
        synchronized (this) {
            checkCredentialState(expected); generation++; pending.forEach(query -> query.cancel(true));
            funds.clear(OpenAiBalanceStore.account(key));
        }
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
                synchronized (this) { captured = generation; checkCredentialState(captured); }
                String key = official ? adminKey(captured) : null;
                identity = official ? OpenAiBalanceStore.account(key) : null;
                client = official ? admins.apply(key) : providers.apply(connection);
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

    @Override public void close() {
        synchronized (this) {
            closed = true; generation++; sessionAdminKey = null; environmentDisabled = true; credentialsChanging = false;
            pending.forEach(query -> query.cancel(true)); credentialTasks.forEach(task -> task.cancel(true));
            credentialExecutor.shutdownNow();
        }
        keys.close();
    }
}
