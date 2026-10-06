package app.voice;

import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GptSovitsConfigTest {
    @Test
    void existingConstructorKeepsLocalFileValidation() {
        GptSovitsConfig config = new GptSovitsConfig(
                URI.create("http://tts.example.invalid:9880"),
                Path.of("/srv/tts/model.ckpt"), Path.of("/srv/tts/model.pth"),
                new VoiceProfile("neutral", Path.of("/srv/tts/neutral.wav"), "参考文本。", "zh"),
                null, null);

        assertFalse(config.isRemoteServer());
        assertFalse(VoiceProfileRegistry.fromConfig(config, message -> { }).isAvailable());
    }

    @Test
    void remotePropertyWorksThroughLocalhostTunnel() {
        Properties properties = requiredProperties();
        properties.setProperty(GptSovitsConfig.PROPERTY_REMOTE_SERVER, "true");

        GptSovitsConfig config = GptSovitsConfig.fromProperties(properties);

        assertTrue(config.isRemoteServer());
        assertTrue(VoiceProfileRegistry.fromConfig(config, message -> { }).isAvailable());
    }

    @Test
    void missingRemotePropertyDefaultsToLocalMode() {
        assertFalse(GptSovitsConfig.fromProperties(requiredProperties()).isRemoteServer());
    }

    @Test
    void remoteModeDoesNotBypassRequiredConfiguration() {
        Properties properties = requiredProperties();
        properties.setProperty(GptSovitsConfig.PROPERTY_REMOTE_SERVER, "true");
        properties.remove("gptSovits.profile.neutral.promptText");

        assertThrows(VoiceSynthesisException.class, () -> GptSovitsConfig.fromProperties(properties));
    }

    @Test
    void streamingIsOptInAndCanBeEnabledForTheRemoteAdapter() {
        Properties properties = requiredProperties();
        assertFalse(GptSovitsConfig.fromProperties(properties).isStreaming());
        properties.setProperty(GptSovitsConfig.PROPERTY_STREAMING, "true");
        assertTrue(GptSovitsConfig.fromProperties(properties).isStreaming());
    }

    @Test
    void invalidRemotePropertyIsRejected() {
        Properties properties = requiredProperties();
        properties.setProperty(GptSovitsConfig.PROPERTY_REMOTE_SERVER, "invalid");

        assertThrows(VoiceSynthesisException.class, () -> GptSovitsConfig.fromProperties(properties));
    }

    private Properties requiredProperties() {
        Properties properties = new Properties();
        properties.setProperty(GptSovitsConfig.PROPERTY_BASE_URL, "http://127.0.0.1:9880");
        properties.setProperty(GptSovitsConfig.PROPERTY_GPT_WEIGHT_PATH, "/srv/tts/model.ckpt");
        properties.setProperty(GptSovitsConfig.PROPERTY_SOVITS_WEIGHT_PATH, "/srv/tts/model.pth");
        properties.setProperty("gptSovits.profile.neutral.refAudioPath", "/srv/tts/neutral.wav");
        properties.setProperty("gptSovits.profile.neutral.promptText", "参考文本。");
        return properties;
    }
}
