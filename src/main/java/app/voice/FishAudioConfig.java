package app.voice;

import app.voice.model.VoiceProfile;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Opt-in hosted voice settings, separate from the existing local voice configuration. */
public final class FishAudioConfig {
    public static final String BACKEND = "fish";
    public static final String LEGACY_DRAMA_BACKEND = "fish-drama3";
    public static final String FREE_MODEL = "s2.1-pro-free";
    public static final String DRAMA_MODEL = "drama-3";
    public static final String DEFAULT_REFERENCE_ID = "4632f51d7f524e6da1952135e498a6a3";
    public static final String ENV_API_KEY = "CLOSURE_FISH_API_KEY";
    public static final String ENV_REFERENCE_ID = "CLOSURE_FISH_REFERENCE_ID";
    public static final String ENV_BASE_URL = "CLOSURE_FISH_BASE_URL";
    public static final String ENV_MODEL = "CLOSURE_FISH_MODEL";
    public static final String PROPERTY_API_KEY = "fish.apiKey";
    public static final String PROPERTY_REFERENCE_ID = "fish.referenceId";
    public static final String PROPERTY_BASE_URL = "fish.baseUrl";
    public static final String PROPERTY_MODEL = "fish.model";
    private static final Path LOCAL_CONFIG = Path.of("config", "local-fish-voice.properties");

    private final String apiKey;
    private final String referenceId;
    private final String model;
    private final URI baseUri;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final boolean autoSpeakEnabled;

    private FishAudioConfig(Map<String, String> environment, Properties properties) {
        String defaultModel = LEGACY_DRAMA_BACKEND.equals(environment.get(GptSovitsConfig.ENV_BACKEND))
                ? DRAMA_MODEL : FREE_MODEL;
        model = value(environment, properties, ENV_MODEL, PROPERTY_MODEL, defaultModel);
        if (!FREE_MODEL.equals(model) && !DRAMA_MODEL.equals(model)) {
            throw new VoiceSynthesisException("fish.model 只接受 s2.1-pro-free 或 drama-3，请明确选择；不会自动切换模型。");
        }
        apiKey = value(environment, properties, ENV_API_KEY, PROPERTY_API_KEY, "");
        if (apiKey.isEmpty()) {
            throw new VoiceSynthesisException("缺少 Fish API 凭据：请在本机配置 CLOSURE_FISH_API_KEY 或 fish.apiKey。");
        }
        if (apiKey.length() > 512 || !apiKey.matches("[A-Za-z0-9._~+/=-]+")) {
            throw new VoiceSynthesisException("Fish API 凭据格式无效，请检查本机配置。");
        }
        referenceId = value(environment, properties, ENV_REFERENCE_ID, PROPERTY_REFERENCE_ID,
                DEFAULT_REFERENCE_ID).toLowerCase(Locale.ROOT);
        if (!referenceId.matches("[a-f0-9]{32}")) {
            throw new VoiceSynthesisException("fish.referenceId 必须是音源页面中的 32 位 ID。");
        }
        baseUri = requireBaseUri(value(environment, properties, ENV_BASE_URL, PROPERTY_BASE_URL,
                "https://api.fish.audio"));
        connectTimeout = Duration.ofSeconds(seconds(environment, properties,
                "CLOSURE_FISH_CONNECT_TIMEOUT_SECONDS", "fish.connectTimeoutSeconds", 10, 60));
        requestTimeout = Duration.ofSeconds(seconds(environment, properties,
                "CLOSURE_FISH_REQUEST_TIMEOUT_SECONDS", "fish.requestTimeoutSeconds", 60, 300));
        String autoSpeak = value(environment, properties, "CLOSURE_VOICE_AUTO_SPEAK", "voice.autoSpeak", "true");
        if (!autoSpeak.equalsIgnoreCase("true") && !autoSpeak.equalsIgnoreCase("false")) {
            throw new VoiceSynthesisException("voice.autoSpeak 必须是 true 或 false。");
        }
        autoSpeakEnabled = Boolean.parseBoolean(autoSpeak);
    }

    /** Merely inspecting backend selection must not load credentials or local voice files. */
    public static boolean isSelected() {
        return isSelected(System.getenv());
    }

    static boolean isSelected(Map<String, String> environment) {
        return isHostedBackend(environment.get(GptSovitsConfig.ENV_BACKEND));
    }

    public static boolean isHostedBackend(String backend) {
        return BACKEND.equals(backend) || LEGACY_DRAMA_BACKEND.equals(backend);
    }

    public static FishAudioConfig fromEnvironment() {
        Properties properties = new Properties();
        if (Files.exists(LOCAL_CONFIG)) {
            try (Reader reader = Files.newBufferedReader(LOCAL_CONFIG, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException exception) {
                // Do not attach a cause that may quote credential-bearing properties.
                throw new VoiceSynthesisException("读取 Fish 本地配置失败，请检查 config/local-fish-voice.properties。");
            }
        }
        return from(System.getenv(), properties);
    }

    /** Pure parsing for tests and explicit application configuration, without any file access. */
    public static FishAudioConfig fromProperties(Properties properties) {
        return from(Map.of(), properties);
    }

    static FishAudioConfig from(Map<String, String> environment, Properties properties) {
        return new FishAudioConfig(Objects.requireNonNull(environment), Objects.requireNonNull(properties));
    }

    public URI getBaseUri() { return baseUri; }
    public String getApiKey() { return apiKey; }
    public String getReferenceId() { return referenceId; }
    public String getModel() { return model; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public boolean isAutoSpeakEnabled() { return autoSpeakEnabled; }

    public VoiceProfile getVoiceProfile() {
        // An opaque hosted-reference marker, never opened or uploaded as a local audio file.
        return new VoiceProfile("fish-closure", Path.of("fish-hosted", referenceId),
                "Hosted Fish Audio reference", "zh", 1.0, 30, 0.7, 0.7);
    }

    private static String value(Map<String, String> environment, Properties properties,
                                String environmentName, String propertyName, String fallback) {
        String selected = environment.get(environmentName);
        if (selected == null || selected.isBlank()) selected = properties.getProperty(propertyName);
        return selected == null || selected.isBlank() ? fallback : selected.trim();
    }

    private static int seconds(Map<String, String> environment, Properties properties,
                               String environmentName, String propertyName, int fallback, int maximum) {
        String raw = value(environment, properties, environmentName, propertyName, Integer.toString(fallback));
        try {
            int seconds = Integer.parseInt(raw);
            if (seconds >= 1 && seconds <= maximum) return seconds;
        } catch (NumberFormatException ignored) { }
        throw new VoiceSynthesisException(propertyName + " 必须是 1–" + maximum + " 的整数。");
    }

    private static URI requireBaseUri(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException exception) {
            throw new VoiceSynthesisException("fish.baseUrl 必须是有效的 HTTPS 服务地址。");
        }
        String host = uri.getHost();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        boolean scheme = "https".equalsIgnoreCase(uri.getScheme())
                || (loopback && "http".equalsIgnoreCase(uri.getScheme()));
        if (!scheme || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw new VoiceSynthesisException("fish.baseUrl 仅接受 HTTPS 服务根地址；本机回环测试可使用 HTTP。");
        }
        return URI.create(uri.toString().replaceAll("/$", ""));
    }
}
