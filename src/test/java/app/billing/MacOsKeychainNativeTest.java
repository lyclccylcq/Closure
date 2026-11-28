package app.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named="closure.nativeKeychainTest", matches="true")
class MacOsKeychainNativeTest {
    @Test void isolatedSyntheticItemSurvivesStoreCloseAndSupportsUpdateAndDelete() {
        String service = "com.closure.tests.admin." + UUID.randomUUID();
        String key = "synthetic-admin-" + UUID.randomUUID();
        try {
            try (var first = new MacOsAdminKeyStore(service, "test-only")) {
                assertFalse(first.contains()); assertNull(first.load()); first.save(key);
                assertTrue(first.contains()); assertEquals(key, first.load());
            }
            try (var second = new MacOsAdminKeyStore(service, "test-only")) {
                String replacement = key + "-'\"\\replacement";
                assertEquals(key, second.load()); second.save(replacement);
                assertEquals(replacement, second.load());
                String longKey = "synthetic-" + "x".repeat(4086);
                assertThrows(BalanceException.class, () -> second.save(longKey));
                assertEquals(replacement, second.load(), "Rejected long input must preserve the previous credential");
                String prefix = "add-generic-password -U -s " + MacOsAdminKeyStore.quote(service)
                        + " -a " + MacOsAdminKeyStore.quote("test-only") + " -w \"";
                String boundaryKey = "x".repeat(MacOsAdminKeyStore.MAX_INTERACTIVE_LINE_BYTES - prefix.length() - 2);
                second.save(boundaryKey); assertEquals(boundaryKey, second.load());
                second.clear(); assertFalse(second.contains()); assertNull(second.load()); second.clear();
            }
            System.out.println("CLOSURE_KEYCHAIN_NATIVE_PASS isolatedSyntheticItem=true stdinOnly=true reopen=true update=true longInputGuard=true delete=true");
        } finally { try (var cleanup = new MacOsAdminKeyStore(service, "test-only")) { cleanup.clear(); } }
    }
}
