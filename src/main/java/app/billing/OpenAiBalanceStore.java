package app.billing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** Explicit local calibration only. No credentials or provider response bodies are persisted. */
final class OpenAiBalanceStore {
    record Entry(String account, BigDecimal balance, BigDecimal baseline, Instant from, Instant syncedAt) {
        Entry {
            if (!account.matches("[0-9a-f]{64}") || balance.signum() < 0 || from.isAfter(syncedAt)
                    || from.getNano() != 0 || Math.floorMod(from.getEpochSecond(), 86400) != 0) throw BalanceJson.invalid();
        }
        BalanceReport.Funding estimate(BalanceReport.CostSnapshot costs) {
            if (costs == null || !costs.usdOnly() || !from.equals(costs.from()) || costs.through().isBefore(syncedAt)
                    || costs.usdTotal().compareTo(baseline) < 0) return null;
            return new BalanceReport.Funding(balance.subtract(costs.usdTotal().subtract(baseline)), syncedAt);
        }
    }
    private final Path file;
    private Entry memory;
    OpenAiBalanceStore(Path file) { this.file = file; }
    static OpenAiBalanceStore local() {
        return new OpenAiBalanceStore(Path.of(System.getProperty("user.home"), ".closure", "billing", "openai-funds.json"));
    }
    static String account(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    synchronized Entry load(String account) {
        if (file == null) return memory != null && memory.account().equals(account) ? memory : null;
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 8192) return null;
            Map<String,Object> json = BalanceJson.read(Files.readString(file, StandardCharsets.UTF_8));
            if (BalanceJson.number(json.get("version")).compareTo(BigDecimal.ONE) != 0) return null;
            Entry value = new Entry(BalanceJson.string(json.get("account")), BalanceJson.number(json.get("balance")),
                    BalanceJson.number(json.get("baseline")), Instant.parse(BalanceJson.string(json.get("from"))),
                    Instant.parse(BalanceJson.string(json.get("synced_at"))));
            return value.account().equals(account) ? value : null;
        } catch (Exception invalid) { return null; } // A damaged or stale record asks for calibration, never a zero balance.
    }

    synchronized void save(Entry value) {
        if (file == null) { memory = value; return; }
        Path temporary = null;
        try {
            Path parent = file.toAbsolutePath().getParent(); Files.createDirectories(parent);
            boolean posix = Files.getFileStore(parent).supportsFileAttributeView("posix");
            if (posix) Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
            temporary = Files.createTempFile(parent, ".funds-", ".tmp");
            if (posix) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            String json = "{\"version\":1,\"account\":\"" + value.account() + "\",\"balance\":\"" + value.balance().toPlainString()
                    + "\",\"baseline\":\"" + value.baseline().toPlainString() + "\",\"from\":\"" + value.from()
                    + "\",\"synced_at\":\"" + value.syncedAt() + "\"}\n";
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unavailable) { throw new BalanceException("余额同步未能保存，请检查本机存储权限后重试。"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
    }

    synchronized void clear(String account) {
        if (file == null) { if (memory != null && memory.account().equals(account)) memory = null; return; }
        try { if (load(account) != null) Files.deleteIfExists(file); }
        catch (Exception unavailable) { throw new BalanceException("同步余额暂时无法清除，请稍后重试。"); }
    }
}
