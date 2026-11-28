package app.billing;

import app.ai.AiModelConfig;
import java.net.URI;
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
    private String sessionAdminKey;
    private boolean environmentDisabled;
    private boolean closed;
    private final Set<CompletableFuture<BalanceReport>> pending = ConcurrentHashMap.newKeySet();

    RuntimeBalanceService() {
        this(AiModelConfig::fromEnvironment, () -> System.getenv(ADMIN_KEY_ENV),
                OpenAiAdminClient::new, ApiBalanceClient::new);
    }

    RuntimeBalanceService(Supplier<AiModelConfig> connections, Supplier<String> environmentAdminKey,
                          Function<String,BalanceService> admins, Function<AiModelConfig,BalanceService> providers) {
        this.connections = connections; this.environmentAdminKey = environmentAdminKey;
        this.admins = admins; this.providers = providers;
    }

    @Override public boolean canConfigureOpenAiAdminKey() { return true; }

    @Override public synchronized void configureOpenAiAdminKey(String key) {
        if (closed) throw new BalanceException("账户查询已关闭。");
        sessionAdminKey = OpenAiAdminClient.normalizeKey(key);
        environmentDisabled = false;
    }

    @Override public synchronized void clearOpenAiAdminKey() {
        sessionAdminKey = null;
        environmentDisabled = true;
    }

    private synchronized String adminKey() {
        if (closed) throw new BalanceException("账户查询已关闭。");
        return OpenAiAdminClient.normalizeKey(sessionAdminKey != null ? sessionAdminKey
                : environmentDisabled ? null : environmentAdminKey.get());
    }

    @Override public CompletableFuture<BalanceReport> query() {
        CompletableFuture<BalanceReport> result = new CompletableFuture<>();
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
                BalanceService client = officialOpenAi(connection.getEndpoint())
                        ? admins.apply(adminKey()) : providers.apply(connection);
                if (result.isDone()) return;
                CompletableFuture<BalanceReport> query = client.query();
                active.set(query);
                if (result.isDone()) query.cancel(true);
                query.whenComplete((value, error) -> {
                    if (error == null) result.complete(value); else result.completeExceptionally(error);
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
