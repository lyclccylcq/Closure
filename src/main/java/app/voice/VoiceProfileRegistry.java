package app.voice;

import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;

import java.nio.file.Files;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 管理情绪到 GPT-SoVITS 参考音频配置的映射。
 */
public final class VoiceProfileRegistry {
    private final Map<SpeechEmotion, VoiceProfile> profiles;
    private final Consumer<String> warningSink;
    private final boolean remoteServer;
    private final Set<String> warnedKeys = new HashSet<>();
    private final VoiceProfile neutralProfile;

    public VoiceProfileRegistry(Map<SpeechEmotion, VoiceProfile> profiles, Consumer<String> warningSink) {
        this(profiles, warningSink, false);
    }

    public VoiceProfileRegistry(
            Map<SpeechEmotion, VoiceProfile> profiles,
            Consumer<String> warningSink,
            boolean remoteServer
    ) {
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.remoteServer = remoteServer;
        this.profiles = normalizeProfiles(profiles);
        VoiceProfile configuredNeutral = this.profiles.get(SpeechEmotion.NEUTRAL);
        neutralProfile = isReferenceAudioUsable(configuredNeutral) ? configuredNeutral : null;
        if (configuredNeutral != null && neutralProfile == null) {
            warnOnce("neutral-unusable", "neutral 参考音频不可用：" + configuredNeutral.getReferenceAudioPath());
        }
    }

    public static VoiceProfileRegistry fromConfig(GptSovitsConfig config, Consumer<String> warningSink) {
        return new VoiceProfileRegistry(config.getVoiceProfiles(), warningSink, config.isRemoteServer());
    }

    public VoiceProfile get(SpeechEmotion emotion) {
        if (neutralProfile == null) {
            warnOnce("neutral-missing", "neutral 语音 profile 不可用，跳过本次朗读。");
            return null;
        }

        SpeechEmotion normalizedEmotion = activeEmotion(emotion);
        VoiceProfile profile = profiles.get(normalizedEmotion);
        if (isReferenceAudioUsable(profile)) {
            return profile;
        }

        if (normalizedEmotion != SpeechEmotion.NEUTRAL) {
            warnOnce(
                    "fallback-" + normalizedEmotion.name(),
                    normalizedEmotion.name() + " 语音 profile 不可用，回退到 neutral。"
            );
        }

        return neutralProfile;
    }

    public boolean isAvailable() {
        return neutralProfile != null;
    }

    private Map<SpeechEmotion, VoiceProfile> normalizeProfiles(Map<SpeechEmotion, VoiceProfile> sourceProfiles) {
        EnumMap<SpeechEmotion, VoiceProfile> normalizedProfiles = new EnumMap<>(SpeechEmotion.class);
        if (sourceProfiles != null) {
            for (Map.Entry<SpeechEmotion, VoiceProfile> entry : sourceProfiles.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    normalizedProfiles.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return Map.copyOf(normalizedProfiles);
    }

    public boolean isReferenceAudioUsable(VoiceProfile profile) {
        return profile != null
                && !profile.getReferenceAudioPath().toString().isBlank()
                && (remoteServer || Files.isRegularFile(profile.getReferenceAudioPath()));
    }

    private SpeechEmotion activeEmotion(SpeechEmotion emotion) {
        SpeechEmotion normalizedEmotion = emotion == null ? SpeechEmotion.NEUTRAL : emotion;
        return switch (normalizedEmotion) {
            case CHEERFUL, SERIOUS -> SpeechEmotion.NEUTRAL;
            case NEUTRAL, QUESTION, TEASING -> normalizedEmotion;
        };
    }

    private synchronized void warnOnce(String key, String message) {
        if (warnedKeys.add(key)) {
            warningSink.accept(message);
        }
    }
}
