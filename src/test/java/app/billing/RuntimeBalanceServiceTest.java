package app.billing;

import app.ai.AiModelConfig;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBalanceServiceTest {
    private static AiModelConfig connection(String url) {
        return new AiModelConfig(URI.create(url), "ordinary-chat-key", "synthetic-model", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
    @Test void adminAndOrdinaryKeysAreSeparatedAndSessionKeyOverridesOnlyTheAdminEnvironment() {
        var keys = new CopyOnWriteArrayList<String>();
        AiModelConfig config = connection("https://api.openai.com/v1/chat/completions");
        var service = new RuntimeBalanceService(() -> config, () -> "environment-admin-key", key -> {
            keys.add(key); return () -> CompletableFuture.completedFuture(null);
        }, ignored -> { throw new AssertionError("Official OpenAI must use the admin adapter"); });
        assertTrue(keys.isEmpty());
        service.query().join(); service.configureOpenAiAdminKey("session-admin-key"); service.query().join();
        assertEquals(List.of("environment-admin-key", "session-admin-key"), keys);
        assertEquals("ordinary-chat-key", config.getApiKey());
        service.clearOpenAiAdminKey();
        var failure = assertThrows(CompletionException.class, () -> service.query().join());
        assertEquals(BalanceException.Action.CONFIGURE_OPENAI_ADMIN, ((BalanceException) failure.getCause()).action());
        assertEquals(2, keys.size()); service.close();
        assertThrows(BalanceException.class, () -> service.configureOpenAiAdminKey("another-admin-key"));
    }

    @Test void aProxyNeverReceivesTheAdminKeyEvenWhenItSharesAnOpenAiLikeName() {
        for (String url : List.of("https://api.openai.com.example.org/v1/chat/completions", "http://api.openai.com/v1/chat/completions", "https://api.openai.com:8443/v1/chat/completions", "http://127.0.0.1:9999/v1/chat/completions")) {
            AtomicInteger admins = new AtomicInteger(), providers = new AtomicInteger();
            var service = new RuntimeBalanceService(() -> connection(url), () -> "environment-admin-key", key -> {
                admins.incrementAndGet(); throw new AssertionError("Admin key must not be forwarded");
            }, config -> {
                providers.incrementAndGet(); assertEquals("ordinary-chat-key", config.getApiKey());
                return () -> CompletableFuture.completedFuture(null);
            });
            service.configureOpenAiAdminKey("session-admin-key"); service.query().join();
            assertEquals(0, admins.get()); assertEquals(1, providers.get()); service.close();
        }
    }

    @Test void missingOrInvalidAdminKeysAreExplicitAndQueriesCancelWhenTheServiceCloses() throws Exception {
        var waiting = new CompletableFuture<BalanceReport>();
        var started = new java.util.concurrent.CountDownLatch(1);
        var service = new RuntimeBalanceService(() -> connection("https://api.openai.com/v1/chat/completions"), () -> null,
                key -> () -> { started.countDown(); return waiting; }, config -> { throw new AssertionError(); });
        var missing = assertThrows(CompletionException.class, () -> service.query().join());
        assertTrue(missing.getCause() instanceof BalanceException);
        assertThrows(BalanceException.class, () -> service.configureOpenAiAdminKey("bad\nkey"));
        service.configureOpenAiAdminKey("session-admin-key");
        var request = service.query(); assertTrue(started.await(2, TimeUnit.SECONDS));
        service.close(); assertTrue(request.isCancelled());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!waiting.isCancelled() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(waiting.isCancelled());
        assertThrows(CompletionException.class, () -> service.query().join());
    }
}
