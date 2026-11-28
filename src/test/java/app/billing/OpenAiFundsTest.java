package app.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static app.billing.OpenAiFundsFixture.bucket;

class OpenAiFundsTest {
    @TempDir Path directory;
    @Test void synchronizationPersistsExactBaselineAndContinuesAcrossUtcMonthThenRecalibrates() throws Exception {
        Path file = directory.resolve("funds.json");
        try (var fixture = new OpenAiFundsFixture(file)) {
            assertNull(fixture.service().query().join().funding());
            BalanceReport synced = fixture.service().syncOpenAiBalance(new BigDecimal("10.00")).join();
            assertEquals(new BigDecimal("10.000000"), synced.funding().estimatedUsd());
            fixture.setCost("2.000001");
            assertEquals(new BigDecimal("9.234566"), fixture.service().query().join().funding().estimatedUsd());
            assertFalse(Files.readString(file).contains("synthetic-admin-funds"));
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        try (var fixture = new OpenAiFundsFixture(file)) {
            fixture.setNow(Instant.parse("2026-12-01T03:00:00Z"));
            fixture.setBuckets(bucket("2026-11-28", "2.000001", "usd") + "," + bucket("2026-12-01", "1.000001", "usd"));
            BalanceReport nextMonth = fixture.service().query().join();
            assertEquals(new BigDecimal("8.234565"), nextMonth.funding().estimatedUsd());
            assertTrue(nextMonth.speechText().contains("1.0美元"));
            assertTrue(fixture.requests().getLast().uri().getQuery().contains("start_time=" + Instant.parse("2026-11-01T00:00:00Z").getEpochSecond()));
            fixture.setCost("1.000001"); // The new calibration request starts in December, as the real API filters its window.
            fixture.service().syncOpenAiBalance(new BigDecimal("20")).join();
            fixture.setCost("2.000001");
            assertEquals(new BigDecimal("19.000000"), fixture.service().query().join().funding().estimatedUsd());
            assertTrue(fixture.requests().getLast().uri().getQuery().contains("start_time=" + Instant.parse("2026-12-01T00:00:00Z").getEpochSecond()));
        }
    }
    @Test void accountChangesClearAndUnknownCurrencyCannotInventOrReuseFunds() throws Exception {
        Path file = directory.resolve("funds.json");
        try (var fixture = new OpenAiFundsFixture(file)) {
            fixture.service().syncOpenAiBalance(new BigDecimal("4.99")).join();
            assertTrue(fixture.service().query().join().lowFunds());
            fixture.setCost("0.01"); // Missing/corrected historical costs require calibration instead of inventing credits.
            assertNull(fixture.service().query().join().funding());
            fixture.setCost("1.234567");
            fixture.service().configureOpenAiAdminKey("synthetic-different-admin");
            assertNull(fixture.service().query().join().funding());
            fixture.service().configureOpenAiAdminKey("synthetic-admin-funds");
            fixture.service().clearSyncedOpenAiBalance(); assertFalse(Files.exists(file));
            assertFalse(fixture.service().query().join().lowFunds());
            fixture.setBuckets(bucket("2026-11-28", "1.5", "eur"));
            assertThrows(CompletionException.class, () -> fixture.service().syncOpenAiBalance(BigDecimal.TEN).join());
            assertFalse(Files.exists(file));
        }
    }
    @Test void incompleteResponsesAndStorageFailuresNeverCommitASync() throws Exception {
        Path file = directory.resolve("funds.json");
        try (var fixture = new OpenAiFundsFixture(file)) {
            for (String value : new String[]{"-1", "1.0000001"}) assertThrows(CompletionException.class, () -> fixture.service().syncOpenAiBalance(new BigDecimal(value)).join());
            fixture.setStatus(500); assertThrows(CompletionException.class, () -> fixture.service().syncOpenAiBalance(BigDecimal.TEN).join());
            assertFalse(Files.exists(file)); fixture.setStatus(200); fixture.setIncomplete(true);
            assertThrows(CompletionException.class, () -> fixture.service().syncOpenAiBalance(BigDecimal.TEN).join()); assertFalse(Files.exists(file));
        }
        Path blocker = directory.resolve("blocker"); Files.writeString(blocker, "unchanged");
        try (var fixture = new OpenAiFundsFixture(blocker.resolve("funds.json"))) {
            var error = assertThrows(CompletionException.class, () -> fixture.service().syncOpenAiBalance(BigDecimal.TEN).join());
            assertTrue(error.getCause().getMessage().contains("未能保存")); assertEquals("unchanged", Files.readString(blocker));
        }
    }
    @Test void cancellationAndNewCredentialsCannotLetADelayedSyncWriteItsOldAccount() throws Exception {
        var pending = new CompletableFuture<BalanceReport>(); var started = new CountDownLatch(1);
        Path file = directory.resolve("funds.json");
        var service = new RuntimeBalanceService(() -> new app.ai.AiModelConfig(java.net.URI.create("https://api.openai.com/v1/chat/completions"),
                "synthetic-chat", "fixture", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)), () -> "synthetic-admin-funds",
                key -> () -> { started.countDown(); return pending; }, config -> { throw new AssertionError(); }, new OpenAiBalanceStore(file));
        var sync = service.syncOpenAiBalance(BigDecimal.TEN); assertTrue(started.await(2, TimeUnit.SECONDS));
        service.configureOpenAiAdminKey("synthetic-new-account"); assertTrue(sync.isCancelled());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!pending.isCancelled() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(pending.complete(null)); assertFalse(Files.exists(file)); service.close();
    }
    @Test void brokenLocalRecordsAreUnknownAndCanBeReplacedByAnExplicitSync() throws Exception {
        Path file = directory.resolve("funds.json"); Files.writeString(file, "broken local record");
        try (var fixture = new OpenAiFundsFixture(file)) {
            assertNull(fixture.service().query().join().funding());
            assertNotNull(fixture.service().syncOpenAiBalance(BigDecimal.TEN).join().funding());
        }
    }
}
