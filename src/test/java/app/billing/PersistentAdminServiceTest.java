package app.billing;

import app.ai.AiModelConfig;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PersistentAdminServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-28T10:30:00Z");
    private static BalanceReport report() {
        return new BalanceReport("OpenAI", List.of(new BalanceReport.Line("今日费用", new BigDecimal("1.23"), "USD", false, BalanceReport.Kind.COST)), "", NOW,
                new BalanceReport.CostSnapshot(Instant.parse("2026-11-01T00:00:00Z"), NOW, new BigDecimal("1.23"), true), null);
    }
    private static RuntimeBalanceService service(AdminKeyStore keys, OpenAiBalanceStore funds, List<String> used) {
        return new RuntimeBalanceService(() -> new AiModelConfig(URI.create("https://api.openai.com/v1/chat/completions"),
                "ordinary-chat-key", "fixture", Duration.ofSeconds(1), Duration.ofSeconds(1)), () -> "environment-admin-key",
                key -> () -> { used.add(key); return CompletableFuture.completedFuture(report()); }, config -> { throw new AssertionError(); }, funds, keys);
    }
    private static class Store implements AdminKeyStore {
        volatile String value;
        boolean failSave, failClear;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger probes = new AtomicInteger();
        public boolean contains() { probes.incrementAndGet(); return value != null; }
        public String load() { reads.incrementAndGet(); return value; }
        public void save(String key) { if (failSave) throw new BalanceException("模拟保存失败"); value = key; }
        public void clear() { if (failClear) throw new BalanceException("模拟删除失败"); value = null; }
        public boolean persistent() { return true; }
    }
    @Test void savedCredentialsSurviveServiceCloseAndKeepTheirBalanceFingerprint() {
        var store = new Store(); store.value = "previous-saved-admin";
        var funds = new OpenAiBalanceStore(null); var used = new CopyOnWriteArrayList<String>();
        var first = service(store, funds, used);
        assertTrue(first.persistsOpenAiAdminKey()); assertEquals(0, store.reads.get());
        first.query().join(); assertEquals("previous-saved-admin", used.getLast());
        first.configureOpenAiAdminKeyAsync("updated-saved-admin").join();
        first.syncOpenAiBalance(BigDecimal.TEN).join(); first.close();
        assertEquals("updated-saved-admin", store.value);
        var restarted = service(store, funds, used);
        assertNotNull(restarted.query().join().funding()); assertEquals("updated-saved-admin", used.getLast());
        restarted.clearOpenAiAdminKeyAsync().join(); assertNull(store.value);
        assertThrows(CompletionException.class, () -> restarted.query().join()); restarted.close();
        var afterClear = service(store, funds, used);
        afterClear.query().join(); assertEquals("environment-admin-key", used.getLast());
        assertNull(store.value, "Environment credentials must not be silently persisted"); afterClear.close();
    }
    @Test void failuresRetainThePreviousCredentialAndDoNotClaimAChange() {
        var store = new Store(); store.value = "old-saved-admin"; var used = new CopyOnWriteArrayList<String>();
        var service = service(store, new OpenAiBalanceStore(null), used);
        service.query().join(); store.failSave = true;
        assertThrows(CompletionException.class, () -> service.configureOpenAiAdminKeyAsync("new-admin-key").join());
        assertEquals("old-saved-admin", store.value); service.query().join(); assertEquals("old-saved-admin", used.getLast());
        store.failSave = false; service.configureOpenAiAdminKeyAsync("new-admin-key").join(); store.failClear = true;
        assertThrows(CompletionException.class, () -> service.clearOpenAiAdminKeyAsync().join());
        service.query().join(); assertEquals("new-admin-key", used.getLast()); service.close(); assertEquals("new-admin-key", store.value);
    }
    @Test void queuedClearRunsAfterAnOlderSaveEvenWhenTheSaveIsSlow() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var store = new Store() {
            public void save(String key) {
                started.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
                super.save(key);
            }
        };
        var service = service(store, new OpenAiBalanceStore(null), new CopyOnWriteArrayList<>());
        var save = service.configureOpenAiAdminKeyAsync("slow-saved-admin"); assertTrue(started.await(2, TimeUnit.SECONDS));
        var clear = service.clearOpenAiAdminKeyAsync();
        assertThrows(CompletionException.class, () -> service.query().orTimeout(1, TimeUnit.SECONDS).join());
        release.countDown(); save.get(2, TimeUnit.SECONDS); clear.get(2, TimeUnit.SECONDS);
        assertNull(store.value); assertThrows(CompletionException.class, () -> service.query().join()); service.close();
    }
    @Test void cancellationAndCloseDoNotWaitForALockedCredentialRead() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var used = new CopyOnWriteArrayList<String>();
        var store = new Store() {
            public String load() {
                started.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
                return "delayed-admin-key";
            }
        };
        var service = service(store, new OpenAiBalanceStore(null), used); var query = service.query();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTimeout(Duration.ofMillis(300), () -> { assertTrue(query.cancel(true)); service.close(); });
        release.countDown(); assertTrue(query.isCancelled()); assertTrue(used.isEmpty());
    }
    @Test void nonOfficialConnectionsNeverReadSavedAdminCredentials() {
        var store = new Store(); store.value = "saved-admin-key";
        var service = new RuntimeBalanceService(() -> new AiModelConfig(URI.create("https://proxy.example/v1/chat/completions"),
                "ordinary-chat-key", "fixture", Duration.ofSeconds(1), Duration.ofSeconds(1)), () -> "environment-admin-key",
                key -> { throw new AssertionError(); }, config -> () -> CompletableFuture.completedFuture(report()), new OpenAiBalanceStore(null), store);
        service.query().join(); assertEquals(AdminKeyState.UNKNOWN, service.openAiAdminKeyStateAsync().join());
        assertEquals(0, store.reads.get()); assertEquals(0, store.probes.get()); service.close();
    }
    @Test void stateDistinguishesSavedEnvironmentAndMissingWithoutReadingPasswords() {
        var store = new Store(); var service = service(store, new OpenAiBalanceStore(null), new CopyOnWriteArrayList<>());
        assertEquals(AdminKeyState.SESSION, service.openAiAdminKeyStateAsync().join());
        service.configureOpenAiAdminKeyAsync("synthetic-saved-admin").join();
        assertEquals(AdminKeyState.SAVED, service.openAiAdminKeyStateAsync().join());
        assertEquals(0, store.reads.get());
        service.clearOpenAiAdminKeyAsync().join();
        assertEquals(AdminKeyState.MISSING, service.openAiAdminKeyStateAsync().join()); service.close();
        assertThrows(CompletionException.class, () -> service.openAiAdminKeyStateAsync().join());
    }
    @Test void stateLookupsAreSerializedBeforeAQueuedClear() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var store = new Store() {
            public boolean contains() {
                started.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return value != null;
            }
        };
        store.value = "synthetic-saved-admin";
        var service = service(store, new OpenAiBalanceStore(null), new CopyOnWriteArrayList<>());
        var state = service.openAiAdminKeyStateAsync(); assertTrue(started.await(2, TimeUnit.SECONDS));
        var clear = service.clearOpenAiAdminKeyAsync(); release.countDown();
        assertEquals(AdminKeyState.SAVED, state.get(2, TimeUnit.SECONDS)); clear.get(2, TimeUnit.SECONDS);
        assertNull(store.value); assertEquals(AdminKeyState.MISSING, service.openAiAdminKeyStateAsync().join());
        assertTimeout(Duration.ofMillis(300), service::close);
    }
    @Test void closeCancelsAStateLookupWhileTheStoreIsBlocked() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var store = new Store() {
            public boolean contains() {
                started.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return true;
            }
        };
        var service = service(store, new OpenAiBalanceStore(null), new CopyOnWriteArrayList<>());
        var state = service.openAiAdminKeyStateAsync();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTimeout(Duration.ofMillis(300), service::close); assertTrue(state.isCancelled());
        } finally { release.countDown(); service.close(); }
    }
}
