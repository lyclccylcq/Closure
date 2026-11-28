package app.billing;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface BalanceService extends AutoCloseable {
    CompletableFuture<BalanceReport> query();
    default boolean canConfigureOpenAiAdminKey() { return false; }
    default void configureOpenAiAdminKey(String key) { throw new BalanceException("此连接不支持设置 OpenAI 管理密钥。"); }
    default void clearOpenAiAdminKey() { }
    @Override default void close() { }

    /** Resolve connections and credentials only after an explicit query, never at startup. */
    static BalanceService fromEnvironment() { return new RuntimeBalanceService(); }
}
