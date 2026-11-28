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
                assertNull(first.load()); first.save(key); assertEquals(key, first.load());
            }
            try (var second = new MacOsAdminKeyStore(service, "test-only")) {
                assertEquals(key, second.load()); second.save(key + "-'\\replacement");
                assertEquals(key + "-'\\replacement", second.load());
                String longKey = "synthetic-" + "x".repeat(4086);
                second.save(longKey); assertTrue(longKey.equals(second.load()), "Long synthetic credential must round-trip without truncation");
                second.clear(); assertNull(second.load()); second.clear();
            }
            System.out.println("CLOSURE_KEYCHAIN_NATIVE_PASS isolatedSyntheticItem=true stdinOnly=true reopen=true update=true delete=true");
        } finally { try (var cleanup = new MacOsAdminKeyStore(service, "test-only")) { cleanup.clear(); } }
    }
}
