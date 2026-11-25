package app.voice;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FishAudioConfigTest {
    @Test
    void selectionRequiresExactExplicitEnvironmentOptIn() {
        assertFalse(FishAudioConfig.isSelected(Map.of()));
        assertFalse(FishAudioConfig.isSelected(Map.of("CLOSURE_VOICE_BACKEND", "qwen3-tts")));
        assertFalse(FishAudioConfig.isSelected(Map.of("CLOSURE_VOICE_BACKEND", "fish-drama")));
        assertFalse(FishAudioConfig.isSelected(Map.of("CLOSURE_VOICE_BACKEND", "FISH-DRAMA3")));
        assertTrue(FishAudioConfig.isSelected(Map.of("CLOSURE_VOICE_BACKEND", "fish")));
        assertTrue(FishAudioConfig.isSelected(Map.of("CLOSURE_VOICE_BACKEND", "fish-drama3")));
    }

    @Test
    void defaultsUseTheUserReferenceAndHostedProfileWithoutLocalFileRequirements() {
        FishAudioConfig config = FishAudioConfig.fromProperties(required());
        assertEquals("https://api.fish.audio", config.getBaseUri().toString());
        assertEquals("s2.1-pro-free", config.getModel());
        assertEquals("4632f51d7f524e6da1952135e498a6a3", config.getReferenceId());
        assertEquals(Duration.ofSeconds(10), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(60), config.getRequestTimeout());
        assertEquals(1.0, config.getVoiceProfile().getSpeedFactor());
        assertTrue(config.isAutoSpeakEnabled());
    }

    @Test
    void genericBackendDefaultsFreeAndLegacyDramaAliasStaysExplicitlyPaid() {
        assertEquals("s2.1-pro-free", FishAudioConfig.from(
                Map.of("CLOSURE_VOICE_BACKEND", "fish"), required()).getModel());
        assertEquals("drama-3", FishAudioConfig.from(
                Map.of("CLOSURE_VOICE_BACKEND", "fish-drama3"), required()).getModel());
    }

    @Test
    void modelSelectionUsesScopedEnvironmentBeforePropertyAndBackendDefault() {
        Properties properties = required();
        properties.setProperty("fish.model", "drama-3");
        assertEquals("drama-3", FishAudioConfig.fromProperties(properties).getModel());
        assertEquals("s2.1-pro-free", FishAudioConfig.from(
                Map.of("CLOSURE_FISH_MODEL", "s2.1-pro-free"), properties).getModel());
        properties.setProperty("fish.model", "s2.1-pro-free");
        assertEquals("s2.1-pro-free", FishAudioConfig.from(
                Map.of("CLOSURE_VOICE_BACKEND", "fish-drama3"), properties).getModel());
    }

    @Test
    void paidS21AndUnrecognizedModelNamesFailLocallyInsteadOfProviderFallback() {
        for (String model : new String[]{"s2.1-pro", "s2.1", "s2.1-pro-fre", "Drama-3", "unknown-model"}) {
            Properties properties = required();
            properties.setProperty("fish.model", model);
            VoiceSynthesisException error = assertThrows(VoiceSynthesisException.class,
                    () -> FishAudioConfig.fromProperties(properties));
            assertTrue(error.getMessage().contains("fish.model"));
        }
    }

    @Test
    void scopedEnvironmentOverridesPropertiesAndDoesNotUseLegacyProfiles() {
        Properties properties = required();
        properties.setProperty("fish.referenceId", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        properties.setProperty("fish.baseUrl", "https://example.invalid");
        properties.setProperty("voice.autoSpeak", "true");
        properties.setProperty("gptSovits.profile.neutral.refAudioPath", "/does-not-exist/private.wav");
        FishAudioConfig config = FishAudioConfig.from(Map.of(
                "CLOSURE_FISH_API_KEY", "override-token", "CLOSURE_FISH_REFERENCE_ID", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "CLOSURE_FISH_BASE_URL", "http://127.0.0.1:3210/", "CLOSURE_VOICE_AUTO_SPEAK", "false",
                "CLOSURE_FISH_CONNECT_TIMEOUT_SECONDS", "2", "CLOSURE_FISH_REQUEST_TIMEOUT_SECONDS", "3"), properties);
        assertEquals("override-token", config.getApiKey());
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", config.getReferenceId());
        assertEquals("http://127.0.0.1:3210", config.getBaseUri().toString());
        assertEquals(Duration.ofSeconds(2), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(3), config.getRequestTimeout());
        assertFalse(config.isAutoSpeakEnabled());
    }

    @Test
    void credentialIsRequiredAndNeverQuotedByValidationErrors() {
        assertThrows(VoiceSynthesisException.class, () -> FishAudioConfig.fromProperties(new Properties()));
        for (String bad : new String[]{"secret\r\ninjected", "secret token", "secret:"}) {
            Properties properties = required();
            properties.setProperty("fish.apiKey", bad);
            VoiceSynthesisException error = assertThrows(VoiceSynthesisException.class,
                    () -> FishAudioConfig.fromProperties(properties));
            assertFalse(error.toString().contains("secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    void nonLoopbackPlaintextAndCredentialBearingOrNonOriginUrlsAreRejectedWithoutEchoing() {
        for (String bad : new String[]{"http://tts.example.invalid", "https://secret@example.invalid",
                "https://example.invalid/api", "https://example.invalid?secret=token", "https://example.invalid/#secret",
                "https://example.invalid:70000", "https://example.invalid:0", "not a secret uri"}) {
            Properties properties = required();
            properties.setProperty("fish.baseUrl", bad);
            VoiceSynthesisException error = assertThrows(VoiceSynthesisException.class,
                    () -> FishAudioConfig.fromProperties(properties), bad);
            assertFalse(error.toString().contains("secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    void timeoutBooleanAndReferenceValidationIsStrict() {
        for (String[] entry : new String[][]{{"fish.requestTimeoutSeconds", "0"}, {"fish.requestTimeoutSeconds", "301"},
                {"fish.connectTimeoutSeconds", "61"}, {"fish.connectTimeoutSeconds", "NaN"},
                {"voice.autoSpeak", "maybe"}, {"fish.referenceId", "unknown"}, {"fish.referenceId", "a".repeat(33)}}) {
            Properties properties = required();
            properties.setProperty(entry[0], entry[1]);
            assertThrows(VoiceSynthesisException.class, () -> FishAudioConfig.fromProperties(properties));
        }
    }

    private Properties required() {
        Properties properties = new Properties();
        properties.setProperty("fish.apiKey", "synthetic-test-token");
        return properties;
    }
}
