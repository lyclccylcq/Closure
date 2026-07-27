package app.voice;

import app.voice.model.VoiceProfile;
import app.voice.model.SpeechEmotion;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
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
    public static final String ENV_AUTO_SPEAK = "CLOSURE_VOICE_AUTO_SPEAK";
    public static final String ENV_SHORT_TEXT_MAX_CHARS = "CLOSURE_GPT_SOVITS_SHORT_TEXT_MAX_CHARS";
    public static final String ENV_SHORT_TEXT_SPLIT_METHOD = "CLOSURE_GPT_SOVITS_SHORT_TEXT_SPLIT_METHOD";
    public static final String ENV_LONG_TEXT_SPLIT_METHOD = "CLOSURE_GPT_SOVITS_LONG_TEXT_SPLIT_METHOD";
    public static final String ENV_FRAGMENT_INTERVAL = "CLOSURE_GPT_SOVITS_FRAGMENT_INTERVAL";
    public static final String ENV_SEED = "CLOSURE_GPT_SOVITS_SEED";
    public static final String ENV_PARALLEL_INFER = "CLOSURE_GPT_SOVITS_PARALLEL_INFER";
    public static final String ENV_SPLIT_BUCKET = "CLOSURE_GPT_SOVITS_SPLIT_BUCKET";
    public static final String ENV_BATCH_SIZE = "CLOSURE_GPT_SOVITS_BATCH_SIZE";

    public static final String PROPERTY_BASE_URL = "gptSovits.baseUrl";
    public static final String PROPERTY_GPT_WEIGHT_PATH = "gptSovits.gptWeightPath";
    public static final String PROPERTY_SOVITS_WEIGHT_PATH = "gptSovits.sovitsWeightPath";
    public static final String PROPERTY_NEUTRAL_REF_AUDIO_PATH = "gptSovits.neutral.refAudioPath";
    public static final String PROPERTY_NEUTRAL_PROMPT_TEXT = "gptSovits.neutral.promptText";
    public static final String PROPERTY_NEUTRAL_PROMPT_LANG = "gptSovits.neutral.promptLang";
    public static final String PROPERTY_AUTO_SPEAK = "voice.autoSpeak";
    public static final String PROPERTY_SHORT_TEXT_MAX_CHARS = "gptSovits.shortTextMaxChars";
    public static final String PROPERTY_SHORT_TEXT_SPLIT_METHOD = "gptSovits.shortTextSplitMethod";
    public static final String PROPERTY_LONG_TEXT_SPLIT_METHOD = "gptSovits.longTextSplitMethod";
    public static final String PROPERTY_FRAGMENT_INTERVAL = "gptSovits.fragmentInterval";
    public static final String PROPERTY_SEED = "gptSovits.seed";
    public static final String PROPERTY_PARALLEL_INFER = "gptSovits.parallelInfer";
    public static final String PROPERTY_SPLIT_BUCKET = "gptSovits.splitBucket";
    public static final String PROPERTY_BATCH_SIZE = "gptSovits.batchSize";

    private static final String PROFILE_PROPERTY_PREFIX = "gptSovits.profile.";

    private static final Path LOCAL_CONFIG_PATH = Path.of("config", "local-voice.properties");
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:9880";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final int DEFAULT_SHORT_TEXT_MAX_CHARS = 80;
    private static final String DEFAULT_SHORT_TEXT_SPLIT_METHOD = "cut0";
    private static final String DEFAULT_LONG_TEXT_SPLIT_METHOD = "cut5";
    private static final double DEFAULT_FRAGMENT_INTERVAL = 0.12;
    private static final int DEFAULT_SEED = 42;
    private static final boolean DEFAULT_PARALLEL_INFER = true;
    private static final boolean DEFAULT_SPLIT_BUCKET = true;
    private static final int DEFAULT_BATCH_SIZE = 1;

    private final URI baseUri;
    private final Path gptWeightPath;
    private final Path sovitsWeightPath;
    private final VoiceProfile neutralVoiceProfile;
    private final Map<SpeechEmotion, VoiceProfile> voiceProfiles;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final boolean autoSpeakEnabled;
    private final int shortTextMaxChars;
    private final String shortTextSplitMethod;
    private final String longTextSplitMethod;
    private final double fragmentInterval;
    private final int seed;
    private final boolean parallelInfer;
    private final boolean splitBucket;
    private final int batchSize;

    public GptSovitsConfig(
            URI baseUri,
            Path gptWeightPath,
            Path sovitsWeightPath,
            VoiceProfile neutralVoiceProfile,
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        this(baseUri, gptWeightPath, sovitsWeightPath, neutralVoiceProfile, connectTimeout, requestTimeout, true);
    }

    public GptSovitsConfig(
            URI baseUri,
            Path gptWeightPath,
            Path sovitsWeightPath,
            VoiceProfile neutralVoiceProfile,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean autoSpeakEnabled
    ) {
        this(
                baseUri,
                gptWeightPath,
                sovitsWeightPath,
                Map.of(SpeechEmotion.NEUTRAL, neutralVoiceProfile),
                connectTimeout,
                requestTimeout,
                autoSpeakEnabled
        );
    }

    public GptSovitsConfig(
            URI baseUri,
            Path gptWeightPath,
            Path sovitsWeightPath,
            Map<SpeechEmotion, VoiceProfile> voiceProfiles,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean autoSpeakEnabled
    ) {
        this(
                baseUri,
                gptWeightPath,
                sovitsWeightPath,
                voiceProfiles,
                connectTimeout,
                requestTimeout,
                autoSpeakEnabled,
                DEFAULT_SHORT_TEXT_MAX_CHARS,
                DEFAULT_SHORT_TEXT_SPLIT_METHOD,
                DEFAULT_LONG_TEXT_SPLIT_METHOD,
                DEFAULT_FRAGMENT_INTERVAL,
                DEFAULT_SEED,
                DEFAULT_PARALLEL_INFER,
                DEFAULT_SPLIT_BUCKET,
                DEFAULT_BATCH_SIZE
        );
    }

    public GptSovitsConfig(
            URI baseUri,
            Path gptWeightPath,
            Path sovitsWeightPath,
            Map<SpeechEmotion, VoiceProfile> voiceProfiles,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean autoSpeakEnabled,
            int shortTextMaxChars,
            String shortTextSplitMethod,
            String longTextSplitMethod,
            double fragmentInterval,
            int seed,
            boolean parallelInfer,
            boolean splitBucket,
            int batchSize
    ) {
        this.baseUri = requireUri(baseUri);
        this.gptWeightPath = requirePath(gptWeightPath, "gptWeightPath");
        this.sovitsWeightPath = requirePath(sovitsWeightPath, "sovitsWeightPath");
        this.voiceProfiles = normalizeVoiceProfiles(voiceProfiles);
        this.neutralVoiceProfile = requireVoiceProfile(this.voiceProfiles.get(SpeechEmotion.NEUTRAL));
        this.connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        this.autoSpeakEnabled = autoSpeakEnabled;
        this.shortTextMaxChars = requirePositive(shortTextMaxChars, "shortTextMaxChars");
        this.shortTextSplitMethod = requireText(shortTextSplitMethod, "shortTextSplitMethod");
        this.longTextSplitMethod = requireText(longTextSplitMethod, "longTextSplitMethod");
        this.fragmentInterval = requirePositive(fragmentInterval, "fragmentInterval");
        this.seed = requireNonNegative(seed, "seed");
        this.parallelInfer = parallelInfer;
        this.splitBucket = splitBucket;
        this.batchSize = requirePositive(batchSize, "batchSize");
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
        Map<SpeechEmotion, VoiceProfile> voiceProfiles = loadVoiceProfiles(localProperties);

        return new GptSovitsConfig(
                URI.create(baseUrl),
                gptWeightPath,
                sovitsWeightPath,
                voiceProfiles,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                autoSpeakEnabled(localProperties),
                configInt(
                        ENV_SHORT_TEXT_MAX_CHARS,
                        PROPERTY_SHORT_TEXT_MAX_CHARS,
                        localProperties,
                        DEFAULT_SHORT_TEXT_MAX_CHARS
                ),
                configValueOrDefault(
                        ENV_SHORT_TEXT_SPLIT_METHOD,
                        PROPERTY_SHORT_TEXT_SPLIT_METHOD,
                        localProperties,
                        DEFAULT_SHORT_TEXT_SPLIT_METHOD
                ),
                configValueOrDefault(
                        ENV_LONG_TEXT_SPLIT_METHOD,
                        PROPERTY_LONG_TEXT_SPLIT_METHOD,
                        localProperties,
                        DEFAULT_LONG_TEXT_SPLIT_METHOD
                ),
                configDouble(
                        ENV_FRAGMENT_INTERVAL,
                        PROPERTY_FRAGMENT_INTERVAL,
                        localProperties,
                        DEFAULT_FRAGMENT_INTERVAL
                ),
                configInt(ENV_SEED, PROPERTY_SEED, localProperties, DEFAULT_SEED),
                configBoolean(
                        ENV_PARALLEL_INFER,
                        PROPERTY_PARALLEL_INFER,
                        localProperties,
                        DEFAULT_PARALLEL_INFER
                ),
                configBoolean(
                        ENV_SPLIT_BUCKET,
                        PROPERTY_SPLIT_BUCKET,
                        localProperties,
                        DEFAULT_SPLIT_BUCKET
                ),
                configInt(ENV_BATCH_SIZE, PROPERTY_BATCH_SIZE, localProperties, DEFAULT_BATCH_SIZE)
        );
    }

    public static boolean autoSpeakEnabledFromEnvironment() {
        return autoSpeakEnabled(loadLocalProperties());
    }

    public static Path localConfigPath() {
        return LOCAL_CONFIG_PATH;
    }

    public static boolean hasLocalConfigFile() {
        return Files.exists(LOCAL_CONFIG_PATH);
    }

    public static boolean hasRequiredEnvironmentConfig() {
        boolean hasLegacyNeutralEnvironment = hasEnvironmentValue(ENV_NEUTRAL_REF_AUDIO_PATH)
                && hasEnvironmentValue(ENV_NEUTRAL_PROMPT_TEXT);
        boolean hasProfileNeutralEnvironment = hasEnvironmentValue(profileEnvironmentName(
                SpeechEmotion.NEUTRAL,
                "REF_AUDIO_PATH"
        )) && hasEnvironmentValue(profileEnvironmentName(SpeechEmotion.NEUTRAL, "PROMPT_TEXT"));

        return hasEnvironmentValue(ENV_GPT_WEIGHT_PATH)
                && hasEnvironmentValue(ENV_SOVITS_WEIGHT_PATH)
                && (hasLegacyNeutralEnvironment || hasProfileNeutralEnvironment);
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

    public Map<SpeechEmotion, VoiceProfile> getVoiceProfiles() {
        return voiceProfiles;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public boolean isAutoSpeakEnabled() {
        return autoSpeakEnabled;
    }

    public int getShortTextMaxChars() {
        return shortTextMaxChars;
    }

    public String getShortTextSplitMethod() {
        return shortTextSplitMethod;
    }

    public String getLongTextSplitMethod() {
        return longTextSplitMethod;
    }

    public double getFragmentInterval() {
        return fragmentInterval;
    }

    public int getSeed() {
        return seed;
    }

    public boolean isParallelInfer() {
        return parallelInfer;
    }

    public boolean isSplitBucket() {
        return splitBucket;
    }

    public int getBatchSize() {
        return batchSize;
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

    private static Map<SpeechEmotion, VoiceProfile> loadVoiceProfiles(Properties localProperties) {
        EnumMap<SpeechEmotion, VoiceProfile> profiles = new EnumMap<>(SpeechEmotion.class);
        for (SpeechEmotion emotion : SpeechEmotion.values()) {
            VoiceProfile profile = readVoiceProfile(emotion, localProperties);
            if (profile != null) {
                profiles.put(emotion, profile);
            }
        }

        if (!profiles.containsKey(SpeechEmotion.NEUTRAL)) {
            throw new VoiceSynthesisException(
                    "缺少 GPT-SoVITS neutral 语音配置，可以写在 "
                            + profileProperty(SpeechEmotion.NEUTRAL, "refAudioPath")
                            + " / "
                            + profileProperty(SpeechEmotion.NEUTRAL, "promptText")
                            + "，或使用旧配置 "
                            + PROPERTY_NEUTRAL_REF_AUDIO_PATH
            );
        }

        return profiles;
    }

    private static VoiceProfile readVoiceProfile(SpeechEmotion emotion, Properties localProperties) {
        String id = profileId(emotion);
        String refAudioPath = configValue(profileEnvironmentName(emotion, "REF_AUDIO_PATH"),
                profileProperty(emotion, "refAudioPath"), localProperties);
        String promptText = configValue(profileEnvironmentName(emotion, "PROMPT_TEXT"),
                profileProperty(emotion, "promptText"), localProperties);
        String promptLang = configValueOrDefault(profileEnvironmentName(emotion, "PROMPT_LANG"),
                profileProperty(emotion, "promptLang"), localProperties, "zh");

        if (emotion == SpeechEmotion.NEUTRAL) {
            refAudioPath = firstNonBlank(refAudioPath, configValue(
                    ENV_NEUTRAL_REF_AUDIO_PATH,
                    PROPERTY_NEUTRAL_REF_AUDIO_PATH,
                    localProperties
            ));
            promptText = firstNonBlank(promptText, configValue(
                    ENV_NEUTRAL_PROMPT_TEXT,
                    PROPERTY_NEUTRAL_PROMPT_TEXT,
                    localProperties
            ));
            promptLang = firstNonBlank(configValue(
                    ENV_NEUTRAL_PROMPT_LANG,
                    PROPERTY_NEUTRAL_PROMPT_LANG,
                    localProperties
            ), promptLang);
        }

        if (refAudioPath == null || refAudioPath.isBlank() || promptText == null || promptText.isBlank()) {
            return null;
        }

        return new VoiceProfile(
                id,
                Path.of(refAudioPath),
                promptText,
                promptLang,
                profileDouble(localProperties, emotion, "speedFactor", defaultSpeedFactor(emotion)),
                profileInt(localProperties, emotion, "topK", defaultTopK(emotion)),
                profileDouble(localProperties, emotion, "topP", defaultTopP(emotion)),
                profileDouble(localProperties, emotion, "temperature", defaultTemperature(emotion)),
                profileInt(localProperties, emotion, "seed", defaultSeed(emotion)),
                profileDouble(localProperties, emotion, "repetitionPenalty", defaultRepetitionPenalty(emotion))
        );
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

    private static String firstNonBlank(String firstValue, String fallbackValue) {
        if (firstValue != null && !firstValue.isBlank()) {
            return firstValue.trim();
        }
        if (fallbackValue != null && !fallbackValue.isBlank()) {
            return fallbackValue.trim();
        }

        return null;
    }

    private static boolean hasEnvironmentValue(String environmentName) {
        String value = System.getenv(environmentName);
        return value != null && !value.isBlank();
    }

    private static String profileId(SpeechEmotion emotion) {
        return emotion.name().toLowerCase();
    }

    private static String profileProperty(SpeechEmotion emotion, String fieldName) {
        return PROFILE_PROPERTY_PREFIX + profileId(emotion) + "." + fieldName;
    }

    private static String profileEnvironmentName(SpeechEmotion emotion, String suffix) {
        return "CLOSURE_GPT_SOVITS_PROFILE_" + emotion.name() + "_" + suffix;
    }

    private static int configInt(
            String environmentName,
            String propertyName,
            Properties localProperties,
            int defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new VoiceSynthesisException(propertyName + " 必须是整数", exception);
        }
    }

    private static double configDouble(
            String environmentName,
            String propertyName,
            Properties localProperties,
            double defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new VoiceSynthesisException(propertyName + " 必须是数字", exception);
        }
    }

    private static boolean configBoolean(
            String environmentName,
            String propertyName,
            Properties localProperties,
            boolean defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)
                || "0".equals(value)
                || "no".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value)) {
            return false;
        }

        throw new VoiceSynthesisException(propertyName + " 必须是 true 或 false");
    }

    private static int profileInt(
            Properties localProperties,
            SpeechEmotion emotion,
            String fieldName,
            int defaultValue
    ) {
        String value = configValue(
                profileEnvironmentName(emotion, fieldNameToEnvironmentSuffix(fieldName)),
                profileProperty(emotion, fieldName),
                localProperties
        );
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new VoiceSynthesisException(profileProperty(emotion, fieldName) + " 必须是整数", exception);
        }
    }

    private static double profileDouble(
            Properties localProperties,
            SpeechEmotion emotion,
            String fieldName,
            double defaultValue
    ) {
        String value = configValue(
                profileEnvironmentName(emotion, fieldNameToEnvironmentSuffix(fieldName)),
                profileProperty(emotion, fieldName),
                localProperties
        );
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new VoiceSynthesisException(profileProperty(emotion, fieldName) + " 必须是数字", exception);
        }
    }

    private static String fieldNameToEnvironmentSuffix(String fieldName) {
        StringBuilder suffix = new StringBuilder();
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (Character.isUpperCase(character) && !suffix.isEmpty()) {
                suffix.append("_");
            }
            suffix.append(Character.toUpperCase(character));
        }

        return suffix.toString();
    }

    private static double defaultSpeedFactor(SpeechEmotion emotion) {
        return 0.95;
    }

    private static int defaultTopK(SpeechEmotion emotion) {
        return 8;
    }

    private static double defaultTopP(SpeechEmotion emotion) {
        return 0.85;
    }

    private static double defaultTemperature(SpeechEmotion emotion) {
        return 0.7;
    }

    private static int defaultSeed(SpeechEmotion emotion) {
        return 42;
    }

    private static double defaultRepetitionPenalty(SpeechEmotion emotion) {
        return 1.35;
    }

    private static boolean autoSpeakEnabled(Properties localProperties) {
        String value = configValueOrDefault(ENV_AUTO_SPEAK, PROPERTY_AUTO_SPEAK, localProperties, "true");
        if ("true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)
                || "0".equals(value)
                || "no".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value)) {
            return false;
        }

        throw new VoiceSynthesisException(PROPERTY_AUTO_SPEAK + " 必须是 true 或 false");
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

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }

        return value.trim();
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须大于 0");
        }

        return value;
    }

    private static double requirePositive(double value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须大于 0");
        }

        return value;
    }

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " 不能小于 0");
        }

        return value;
    }

    private static VoiceProfile requireVoiceProfile(VoiceProfile voiceProfile) {
        if (voiceProfile == null) {
            throw new IllegalArgumentException("neutralVoiceProfile 不能为空");
        }

        return voiceProfile;
    }

    private static Map<SpeechEmotion, VoiceProfile> normalizeVoiceProfiles(
            Map<SpeechEmotion, VoiceProfile> sourceProfiles
    ) {
        if (sourceProfiles == null || sourceProfiles.isEmpty()) {
            throw new IllegalArgumentException("voiceProfiles 不能为空");
        }

        EnumMap<SpeechEmotion, VoiceProfile> normalizedProfiles = new EnumMap<>(SpeechEmotion.class);
        for (Map.Entry<SpeechEmotion, VoiceProfile> entry : sourceProfiles.entrySet()) {
            SpeechEmotion emotion = entry.getKey();
            VoiceProfile profile = entry.getValue();
            if (emotion != null && profile != null) {
                normalizedProfiles.put(emotion, profile);
            }
        }

        if (!normalizedProfiles.containsKey(SpeechEmotion.NEUTRAL)) {
            throw new IllegalArgumentException("voiceProfiles 必须包含 neutral profile");
        }

        return Map.copyOf(normalizedProfiles);
    }
}
