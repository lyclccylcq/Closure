package app.billing;

import java.util.concurrent.CompletableFuture;
import java.math.BigDecimal;
import java.time.Instant;

@FunctionalInterface
public interface BalanceService extends AutoCloseable {
    CompletableFuture<BalanceReport> query();
    default CompletableFuture<BalanceReport> querySince(Instant since) { return query(); }
    default boolean canSyncOpenAiBalance() { return false; }
    default CompletableFuture<BalanceReport> syncOpenAiBalance(BigDecimal amount) {
        return CompletableFuture.failedFuture(new BalanceException("此连接不支持同步 OpenAI 余额。"));
    }
    default void clearSyncedOpenAiBalance() { }
    default boolean canConfigureOpenAiAdminKey() { return false; }
    default boolean persistsOpenAiAdminKey() { return false; }
    default void configureOpenAiAdminKey(String key) { throw new BalanceException("此连接不支持设置 OpenAI 管理密钥。"); }
    default void clearOpenAiAdminKey() { }
    default CompletableFuture<Void> configureOpenAiAdminKeyAsync(String key) {
        return CompletableFuture.runAsync(() -> configureOpenAiAdminKey(key));
    }
    default CompletableFuture<Void> clearOpenAiAdminKeyAsync() {
        return CompletableFuture.runAsync(this::clearOpenAiAdminKey);
    }
    @Override default void close() { }

    /** Resolve connections and credentials only after an explicit query, never at startup. */
    static BalanceService fromEnvironment() { return new RuntimeBalanceService(); }
}
