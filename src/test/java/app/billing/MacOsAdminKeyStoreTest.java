package app.billing;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MacOsAdminKeyStoreTest {
    @Test void writesUseOnlyStdinAndNoProcessArgumentContainsTheCredential() {
        List<String> operations = new ArrayList<>();
        String secret = "synthetic-key-with-'quotes-and-\\slashes";
        var store = new MacOsAdminKeyStore("synthetic.service", "fixture", (command, input, read) -> {
            assertFalse(command.toString().contains(secret));
            assertEquals("/usr/bin/security", command.getFirst()); operations.add(command.get(1));
            if (command.get(1).equals("-i")) {
                assertTrue(new String(input, StandardCharsets.UTF_8).contains(MacOsAdminKeyStore.quote(secret))); assertFalse(read);
            }
            else assertEquals(0, input.length);
            return new MacOsAdminKeyStore.Result(0, read ? secret + "\n" : "");
        });
        store.save(secret); assertEquals(secret, store.load()); store.clear(); store.close();
        assertEquals(List.of("-i", "find-generic-password", "find-generic-password", "delete-generic-password"), operations);
    }
    @Test void failuresNeverReturnCapturedSecretsOrRawProcessErrors() {
        String secret = "synthetic-do-not-include-in-errors";
        for (boolean throwsError : List.of(false, true)) {
            var store = new MacOsAdminKeyStore("synthetic.service", "fixture", (command, input, read) -> {
                if (throwsError) throw new java.io.IOException(secret);
                return new MacOsAdminKeyStore.Result(1, secret);
            });
            for (Runnable action : List.<Runnable>of(store::load, () -> store.save(secret), store::clear)) {
                var error = assertThrows(BalanceException.class, action::run);
                assertFalse(error.toString().contains(secret)); assertNull(error.getCause());
            }
        }
        assertFalse(new MacOsAdminKeyStore.Result(0, secret).toString().contains(secret));
    }
    @Test void failedInteractiveWritesCannotMasqueradeAsSavedCredentials() {
        var store = new MacOsAdminKeyStore("synthetic.service", "fixture", (command, input, read) ->
                new MacOsAdminKeyStore.Result(0, read ? "old-synthetic-admin" : ""));
        assertThrows(BalanceException.class, () -> store.save("new-synthetic-admin"));
    }
}
