package app.voice;

import app.voice.model.VoiceProfile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * GPT-SoVITS 本地服务配置。
 * 真实路径从环境变量或 config/local-voice.properties 读取，不写死在 Java 类中。
 */
public final class GptSovitsConfig {
    public static final String ENV_BASE_URL = "CLOSURE_GPT_SOVITS_BASE_URL";
    public static final String ENV_GPT_WEIGHT_PATH = "CLOSURE_GPT_SOVITS_GPT_WEIGHT_PATH";
    public static final String ENV_SOVITS_WEIGHT_PATH = "CLOSURE_GPT_SOVITS_SOVITS_WEIGHT_PATH";
    public static final String ENV_NEUTRAL_REF_AUDIO_PATH = "CLOSURE_GPT_SOVITS_NEUTRAL_REF_AUDIO_PATH";
    public static final String ENV_NEUTRAL_PROMPT_TEXT = "CLOSURE_GPT_SOVITS_NEUTRAL_PROMPT_TEXT";
    public static final String ENV_NEUTRAL_PROMPT_LANG = "CLOSURE_GPT_SOVITS_NEUTRAL_PROMPT_LANG";

    public static final String PROPERTY_BASE_URL = "gptSovits.baseUrl";
    public static final String PROPERTY_GPT_WEIGHT_PATH = "gptSovits.gptWeightPath";
    public static final String PROPERTY_SOVITS_WEIGHT_PATH = "gptSovits.sovitsWeightPath";
    public static final String PROPERTY_NEUTRAL_REF_AUDIO_PATH = "gptSovits.neutral.refAudioPath";
    public static final String PROPERTY_NEUTRAL_PROMPT_TEXT = "gptSovits.neutral.promptText";
    public static final String PROPERTY_NEUTRAL_PROMPT_LANG = "gptSovits.neutral.promptLang";

    private static final Path LOCAL_CONFIG_PATH = Path.of("config", "local-voice.properties");
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:9880";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final URI baseUri;
    private final Path gptWeightPath;
    private final Path sovitsWeightPath;
    private final VoiceProfile neutralVoiceProfile;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    public GptSovitsConfig(
            URI baseUri,
            Path gptWeightPath,
            Path sovitsWeightPath,
            VoiceProfile neutralVoiceProfile,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        this.baseUri = requireUri(baseUri);
        this.gptWeightPath = requirePath(gptWeightPath, "gptWeightPath");
        this.sovitsWeightPath = requirePath(sovitsWeightPath, "sovitsWeightPath");
        this.neutralVoiceProfile = requireVoiceProfile(neutralVoiceProfile);
        this.connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }

    public static GptSovitsConfig fromEnvironment() {
        Properties localProperties = loadLocalProperties();

        String baseUrl = configValueOrDefault(ENV_BASE_URL, PROPERTY_BASE_URL, localProperties, DEFAULT_BASE_URL);
        Path gptWeightPath = Path.of(requiredConfigValue(
                ENV_GPT_WEIGHT_PATH,
                PROPERTY_GPT_WEIGHT_PATH,
                localProperties
        ));
        Path sovitsWeightPath = Path.of(requiredConfigValue(
                ENV_SOVITS_WEIGHT_PATH,
                PROPERTY_SOVITS_WEIGHT_PATH,
                localProperties
        ));
        Path neutralRefAudioPath = Path.of(requiredConfigValue(
                ENV_NEUTRAL_REF_AUDIO_PATH,
                PROPERTY_NEUTRAL_REF_AUDIO_PATH,
                localProperties
        ));
        String neutralPromptText = requiredConfigValue(
                ENV_NEUTRAL_PROMPT_TEXT,
                PROPERTY_NEUTRAL_PROMPT_TEXT,
                localProperties
        );
        String neutralPromptLang = configValueOrDefault(
                ENV_NEUTRAL_PROMPT_LANG,
                PROPERTY_NEUTRAL_PROMPT_LANG,
                localProperties,
                "zh"
        );

        return new GptSovitsConfig(
                URI.create(baseUrl),
                gptWeightPath,
                sovitsWeightPath,
                new VoiceProfile("neutral", neutralRefAudioPath, neutralPromptText, neutralPromptLang),
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    public URI getBaseUri() {
        return baseUri;
    }

    public Path getGptWeightPath() {
        return gptWeightPath;
    }

    public Path getSovitsWeightPath() {
        return sovitsWeightPath;
    }

    public VoiceProfile getNeutralVoiceProfile() {
        return neutralVoiceProfile;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    private static Properties loadLocalProperties() {
        Properties properties = new Properties();
        if (!Files.exists(LOCAL_CONFIG_PATH)) {
            return properties;
        }

        try (var reader = Files.newBufferedReader(LOCAL_CONFIG_PATH, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException exception) {
            throw new VoiceSynthesisException("读取本地语音配置失败：" + LOCAL_CONFIG_PATH, exception);
        }
    }

    private static String requiredConfigValue(
            String environmentName,
            String propertyName,
            Properties localProperties
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            throw new VoiceSynthesisException(
                    "缺少 GPT-SoVITS 配置："
                            + propertyName
                            + "，可以写在环境变量 "
                            + environmentName
                            + " 或 "
                            + LOCAL_CONFIG_PATH
            );
        }

        return value;
    }

    private static String configValueOrDefault(
            String environmentName,
            String propertyName,
            Properties localProperties,
            String defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private static String configValue(String environmentName, String propertyName, Properties localProperties) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }

        String localValue = localProperties.getProperty(propertyName);
        if (localValue != null && !localValue.isBlank()) {
            return localValue.trim();
        }

        return null;
    }

    private static URI requireUri(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUri 必须是完整 HTTP 地址");
        }

        return uri;
    }

    private static Path requirePath(Path path, String fieldName) {
        if (path == null || path.toString().isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }

        return path;
    }

    private static VoiceProfile requireVoiceProfile(VoiceProfile voiceProfile) {
        if (voiceProfile == null) {
            throw new IllegalArgumentException("neutralVoiceProfile 不能为空");
        }

        return voiceProfile;
    }
}
