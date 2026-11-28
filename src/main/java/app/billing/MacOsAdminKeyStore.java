package app.billing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** System Keychain bridge. Secrets travel through private pipes, never process arguments or files. */
final class MacOsAdminKeyStore implements AdminKeyStore {
    static final String SERVICE = "com.closure.desktop.openai-admin";
    // security's interactive reader has a bounded command line; reject before a truncated write can occur.
    static final int MAX_INTERACTIVE_LINE_BYTES = 1023;
    private final String service, account;
    private final Runner runner;

    record Result(int status, String output) {
        @Override public String toString() { return "KeychainResult[redacted]"; }
    }
    interface Runner extends AutoCloseable {
        Result run(List<String> command, byte[] input, boolean secretOutput) throws Exception;
        @Override default void close() { }
    }
    MacOsAdminKeyStore() { this(SERVICE, "default", new NativeRunner()); }
    MacOsAdminKeyStore(String service, String account) { this(service, account, new NativeRunner()); }
    MacOsAdminKeyStore(String service, String account, Runner runner) {
        if (!service.matches("[A-Za-z0-9._-]{1,200}") || !account.matches("[A-Za-z0-9._-]{1,200}")) throw new IllegalArgumentException("Invalid credential namespace");
        this.service = service; this.account = account; this.runner = runner;
    }
    @Override public boolean persistent() { return true; }
    @Override public boolean contains() {
        // No -w: check item existence without requesting its password or retaining attributes.
        Result result = execute(List.of("/usr/bin/security", "find-generic-password", "-s", service, "-a", account), null, false);
        if (result.status() == 44) return false;
        if (result.status() != 0) throw unavailable();
        return true;
    }
    @Override public String load() {
        Result result = execute(List.of("/usr/bin/security", "find-generic-password", "-w", "-s", service, "-a", account), null, true);
        if (result.status() == 44) return null; // errSecItemNotFound, as returned by the system tool.
        if (result.status() != 0) throw unavailable();
        String value = result.output().stripTrailing();
        if (value.isEmpty() || value.length() > 4096) throw unavailable();
        return OpenAiAdminClient.normalizeKey(value);
    }
    @Override public void save(String key) {
        String normalized = OpenAiAdminClient.normalizeKey(key);
        String command = "add-generic-password -U -s " + quote(service) + " -a " + quote(account) + " -w " + quote(normalized) + "\n";
        // Namespaces and normalized keys are ASCII, including the escaping added above.
        if (command.length() > MAX_INTERACTIVE_LINE_BYTES) {
            throw new BalanceException("管理密钥过长，无法保存到系统钥匙串。请确认粘贴的是 Admin API key。", BalanceException.Action.CONFIGURE_OPENAI_ADMIN);
        }
        Result result = execute(List.of("/usr/bin/security", "-i"), command, false);
        // Interactive mode may complete successfully even if its command failed. Verify the persisted value.
        if (result.status() != 0 || !normalized.equals(load())) throw unavailable();
    }
    @Override public void clear() {
        Result result = execute(List.of("/usr/bin/security", "delete-generic-password", "-s", service, "-a", account), null, false);
        if (result.status() != 0 && result.status() != 44) throw unavailable();
    }
    @Override public void close() { runner.close(); }

    static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private Result execute(List<String> command, String data, boolean secretOutput) {
        byte[] input = data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8);
        try {
            return runner.run(command, input, secretOutput);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }
    private static BalanceException unavailable() {
        return new BalanceException("系统钥匙串暂时无法访问。请解锁或允许访问后重试，无需反复填写管理密钥。", BalanceException.Action.ACCESS_OPENAI_ADMIN);
    }

    private static final class NativeRunner implements Runner {
        private final Set<Process> active = ConcurrentHashMap.newKeySet();
        private volatile boolean closed;
        @Override public Result run(List<String> command, byte[] input, boolean secretOutput) throws Exception {
            if (closed) throw unavailable();
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            if (!secretOutput) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().entrySet().removeIf(entry -> entry.getKey().matches("(?i).*(TOKEN|SECRET|PASSWORD|API_KEY|CREDENTIAL).*" )
                    || entry.getKey().matches("(?i)^(CLOSURE_|OPENAI_|ANTHROPIC_|FISH_).*"));
            Process process = builder.start(); active.add(process);
            try {
                if (closed) throw unavailable();
                try (var stream = process.getOutputStream()) { stream.write(input); }
                if (!process.waitFor(12, TimeUnit.SECONDS)) throw unavailable();
                if (process.exitValue() != 0) return new Result(process.exitValue(), "");
                if (!secretOutput) return new Result(0, "");
                byte[] output = process.getInputStream().readNBytes(8193);
                try {
                    if (output.length > 8192) throw unavailable();
                    return new Result(0, new String(output, StandardCharsets.UTF_8));
                } finally { Arrays.fill(output, (byte) 0); }
            } finally { active.remove(process); process.destroyForcibly(); }
        }
        @Override public void close() { closed = true; active.forEach(Process::destroyForcibly); }
    }
}
