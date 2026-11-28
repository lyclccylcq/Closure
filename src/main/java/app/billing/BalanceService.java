package app.billing;

import app.ai.AiModelConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@FunctionalInterface
public interface BalanceService {
    CompletableFuture<BalanceReport> query();

    /** Resolve the app's current connection only after an explicit query, never at startup. */
    static BalanceService fromEnvironment() {
        return () -> {
            CompletableFuture<BalanceReport> result = new CompletableFuture<>();
            AtomicReference<CompletableFuture<BalanceReport>> active = new AtomicReference<>();
            CompletableFuture.runAsync(() -> {
                try {
                    if (result.isDone()) return;
                    CompletableFuture<BalanceReport> query = new ApiBalanceClient(AiModelConfig.fromEnvironment()).query();
                    active.set(query);
                    if (result.isDone()) query.cancel(true);
                    query.whenComplete((value, error) -> {
                        if (error == null) result.complete(value); else result.completeExceptionally(error);
                    });
                } catch (RuntimeException error) { result.completeExceptionally(error); }
            });
            result.whenComplete((value, error) -> {
                if (result.isCancelled() && active.get() != null) active.get().cancel(true);
            });
            return result;
        };
    }
}
