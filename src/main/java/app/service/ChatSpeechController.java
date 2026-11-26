package app.service;

import app.chat.model.AssistantReply;
import app.voice.GptSovitsClient;
import app.voice.GptSovitsConfig;
import app.voice.FishAudioClient;
import app.voice.FishAudioConfig;
import app.voice.VoiceProfileRegistry;
import app.voice.VoiceSynthesisException;
import app.voice.VoiceSynthesisClient;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * 自动朗读控制器。
 * UI 只把需要朗读的文本交给这里，具体 GPT-SoVITS 配置和错误处理不散落到 UI 类里。
 */
public final class ChatSpeechController implements AutoCloseable {
    private final AssistantSpeechService speechService;
    private final VoiceProfileRegistry voiceProfileRegistry;
    private final Consumer<String> warningSink;
    private final Throwable unavailableCause;
    private final boolean localQwen;
    private final boolean hostedVoice;
    private final Object requestLock = new Object();

    private CompletableFuture<Void> activeSpeechFuture;
    private Object activeSpeechOwner;
    private CompletableFuture<Void> readyForSpeechFuture;
    private SpeechLifecycleListener speechLifecycleListener = SpeechLifecycleListener.NO_OP;
    private String lastWarningKey;
    private boolean autoSpeakEnabled;
    private boolean speechActive;
    private long requestGeneration;

    public ChatSpeechController(
            AssistantSpeechService speechService,
            VoiceProfileRegistry voiceProfileRegistry,
            boolean autoSpeakEnabled,
            Consumer<String> warningSink
    ) {
        this(speechService, voiceProfileRegistry, autoSpeakEnabled, warningSink, false);
    }

    ChatSpeechController(
            AssistantSpeechService speechService,
            VoiceProfileRegistry voiceProfileRegistry,
            boolean autoSpeakEnabled,
            Consumer<String> warningSink,
            boolean localQwen
    ) {
        this(speechService, voiceProfileRegistry, autoSpeakEnabled, warningSink, localQwen, false);
    }

    ChatSpeechController(
            AssistantSpeechService speechService,
            VoiceProfileRegistry voiceProfileRegistry,
            boolean autoSpeakEnabled,
            Consumer<String> warningSink,
            boolean localQwen,
            boolean hostedVoice
    ) {
        this.speechService = Objects.requireNonNull(speechService, "speechService");
        this.voiceProfileRegistry = Objects.requireNonNull(voiceProfileRegistry, "voiceProfileRegistry");
        this.autoSpeakEnabled = autoSpeakEnabled;
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.localQwen = localQwen;
        this.hostedVoice = hostedVoice;
        unavailableCause = null;
        readyForSpeechFuture = startWarmUp();
    }

    private ChatSpeechController(boolean autoSpeakEnabled, Consumer<String> warningSink, Throwable unavailableCause) {
        this(autoSpeakEnabled, warningSink, unavailableCause, false);
    }

    private ChatSpeechController(boolean autoSpeakEnabled, Consumer<String> warningSink,
                                 Throwable unavailableCause, boolean hostedVoice) {
        speechService = null;
        voiceProfileRegistry = null;
        this.autoSpeakEnabled = autoSpeakEnabled;
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.unavailableCause = Objects.requireNonNull(unavailableCause, "unavailableCause");
        localQwen = false;
        this.hostedVoice = hostedVoice;
        readyForSpeechFuture = CompletableFuture.failedFuture(unavailableCause);
    }

    public static ChatSpeechController fromEnvironment() {
        return fromEnvironment(message -> System.out.println("自动朗读警告：" + message));
    }

    public static ChatSpeechController fromEnvironment(Consumer<String> warningSink) {
        if (FishAudioConfig.isSelected()) {
            try {
                FishAudioConfig config = FishAudioConfig.fromEnvironment();
                ChatSpeechController controller = fromFishConfig(
                        config, new FishAudioClient(config), new AudioPlayerService(), warningSink);
                System.out.println("CLOSURE_VOICE_CONFIGURED fish model=" + config.getModel());
                return controller;
            } catch (RuntimeException exception) {
                return new ChatSpeechController(true, warningSink, exception, true);
            }
        }
        boolean autoSpeakEnabled = autoSpeakEnabledFromConfig(warningSink);
        if (!GptSovitsConfig.hasLocalConfigFile() && !GptSovitsConfig.hasRequiredEnvironmentConfig()) {
            return unavailable(
                    autoSpeakEnabled,
                    warningSink,
                    new VoiceSynthesisException("本地语音配置不存在：" + GptSovitsConfig.localConfigPath())
            );
        }

        try {
            GptSovitsConfig config = GptSovitsConfig.fromEnvironment();
            VoiceProfileRegistry voiceProfileRegistry = VoiceProfileRegistry.fromConfig(config, warningSink);
            return new ChatSpeechController(
                    new SpeechService(
                            new GptSovitsClient(config),
                            new AudioPlayerService(),
                            voiceProfileRegistry,
                            config.isQwenTts()
                    ),
                    voiceProfileRegistry,
                    config.isAutoSpeakEnabled(),
                    warningSink,
                    config.isQwenTts()
            );
        } catch (RuntimeException exception) {
            return unavailable(autoSpeakEnabled, warningSink, exception);
        }
    }

    static ChatSpeechController fromFishConfig(FishAudioConfig config, VoiceSynthesisClient client,
                                               VoiceAudioPlayer player, Consumer<String> warningSink) {
        var profiles = new VoiceProfileRegistry(
                Map.of(SpeechEmotion.NEUTRAL, config.getVoiceProfile()), warningSink, true);
        return new ChatSpeechController(SpeechService.forHostedVoice(client, player),
                profiles, config.isAutoSpeakEnabled(), warningSink, false, true);
    }

    private static ChatSpeechController unavailable(
            boolean autoSpeakEnabled,
            Consumer<String> warningSink,
            Throwable unavailableCause
    ) {
        return new ChatSpeechController(autoSpeakEnabled, warningSink, unavailableCause);
    }

    private static boolean autoSpeakEnabledFromConfig(Consumer<String> warningSink) {
        try {
            return GptSovitsConfig.autoSpeakEnabledFromEnvironment();
        } catch (RuntimeException exception) {
            warningSink.accept("读取自动朗读开关失败，先按默认开启处理：" + exception.getMessage());
            return true;
        }
    }

    public synchronized boolean isAutoSpeakEnabled() {
        return autoSpeakEnabled;
    }

    public void setAutoSpeakEnabled(boolean autoSpeakEnabled) {
        boolean shouldStop;
        synchronized (this) {
            shouldStop = this.autoSpeakEnabled && !autoSpeakEnabled;
            this.autoSpeakEnabled = autoSpeakEnabled;
        }

        if (shouldStop) {
            stop();
        }
    }

    public CompletableFuture<Void> readyForSpeechFuture() {
        return readyForSpeechFuture;
    }

    public boolean usesLocalQwen() {
        return localQwen;
    }

    public boolean usesHostedVoice() {
        return hostedVoice;
    }

    public synchronized void setSpeechLifecycleListener(SpeechLifecycleListener speechLifecycleListener) {
        this.speechLifecycleListener = speechLifecycleListener == null
                ? SpeechLifecycleListener.NO_OP
                : speechLifecycleListener;
    }

    public void speakAssistantReply(String replyText) {
        speakText(replyText);
    }

    public void speakAssistantReply(AssistantReply assistantReply) {
        speakAssistantReplyAsync(assistantReply);
    }

    public SpeechSession createSession() {
        return new SpeechSession();
    }

    public final class SpeechSession {
        private SpeechSession() {}

        public CompletableFuture<Void> speak(AssistantReply reply) {
            synchronized (requestLock) {
                return reply == null ? CompletableFuture.completedFuture(null)
                        : requestSpeech(reply.getSpeechText(), reply.getDominantEmotion(), false, this);
            }
        }

        public void stop() {
            synchronized (requestLock) {
                boolean ownsSpeech;
                synchronized (ChatSpeechController.this) { ownsSpeech = activeSpeechOwner == this; }
                if (ownsSpeech) ChatSpeechController.this.stop();
            }
        }
    }

    public CompletableFuture<Void> speakAssistantReplyAsync(AssistantReply assistantReply) {
        if (assistantReply != null) {
            return speakWithEmotionAsync(assistantReply.getSpeechText(), assistantReply.getDominantEmotion());
        }

        return CompletableFuture.completedFuture(null);
    }

    public void speakText(String text) {
        speakTextAsync(text);
    }

    public CompletableFuture<Void> speakTextAsync(String text) {
        String speechText = SpeechTextSanitizer.sanitizeSegment(text);
        if (!speechText.isBlank()) {
            return speakWithEmotionAsync(speechText, SpeechEmotion.NEUTRAL);
        }

        return CompletableFuture.completedFuture(null);
    }

    public void speakFullText(String text) {
        speakFullTextAsync(text);
    }

    /** Automatic reminders and idle chatter remain text-only in the hosted preview. */
    public void speakBackgroundText(String text) {
        if (!hostedVoice) speakFullText(text);
    }

    public CompletableFuture<Void> speakFullTextAsync(String text) {
        String speechText = SpeechTextSanitizer.sanitizeFull(text);
        if (!speechText.isBlank()) {
            return speakWithEmotionAsync(speechText, SpeechEmotion.NEUTRAL);
        }

        return CompletableFuture.completedFuture(null);
    }

    /** Reuse generated audio only for the pet's fixed touch lines, never arbitrary chat. */
    public void speakFixedPhrase(String text) {
        speakFixedPhraseAsync(text);
    }

    public CompletableFuture<Void> speakFixedPhraseAsync(String text) {
        String speechText = SpeechTextSanitizer.sanitizeFull(text);
        synchronized (requestLock) {
            return requestSpeech(speechText, SpeechEmotion.NEUTRAL, true);
        }
    }

    /** A late background greeting must never cancel a foreground speech request. */
    public CompletableFuture<Void> speakStartupGreetingIfIdle(String text) {
        synchronized (requestLock) {
            if (hostedVoice) return CompletableFuture.completedFuture(null);
            if (requestGeneration != 0) return CompletableFuture.completedFuture(null);
            return speakFullTextAsync(text);
        }
    }

    public void speakWithEmotion(String speechText, SpeechEmotion emotion) {
        speakWithEmotionAsync(speechText, emotion);
    }

    public CompletableFuture<Void> speakWithEmotionAsync(String speechText, SpeechEmotion emotion) {
        synchronized (requestLock) {
            return requestSpeech(speechText, emotion);
        }
    }

    private CompletableFuture<Void> requestSpeech(String speechText, SpeechEmotion emotion) {
        return requestSpeech(speechText, emotion, false);
    }

    private CompletableFuture<Void> requestSpeech(String speechText, SpeechEmotion emotion, boolean fixedPhrase) {
        return requestSpeech(speechText, emotion, fixedPhrase, null);
    }

    private CompletableFuture<Void> requestSpeech(String speechText, SpeechEmotion emotion, boolean fixedPhrase,
                                                   Object owner) {
        if (!isAutoSpeakEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        if (speechText == null || speechText.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        if (unavailableCause != null || speechService == null || voiceProfileRegistry == null) {
            Throwable cause = unavailableCause == null
                    ? new VoiceSynthesisException("语音服务未初始化")
                    : unavailableCause;
            warnOnce("unavailable", warningMessage(cause));
            return CompletableFuture.failedFuture(cause);
        }

        // Independent Qwen reference clips can change speaker identity as well as prosody.
        // Keep its speaker anchor stable; the original SoVITS path retains emotion profiles.
        SpeechEmotion profileEmotion = localQwen || hostedVoice
                ? SpeechEmotion.NEUTRAL : SpeechProsody.emotionFor(speechText, emotion);
        VoiceProfile profile = voiceProfileRegistry.get(profileEmotion);
        if (profile == null) {
            return CompletableFuture.completedFuture(null);
        }

        long requestId = startNewRequest(owner);
        CompletableFuture<Void> speechFuture;
        try {
            speechFuture = fixedPhrase ? speechService.speakFixedPhrase(speechText, profile)
                    : speechService.speak(speechText, profile);
        } catch (RuntimeException exception) {
            notifySpeechFinished();
            warnOnce(errorKey(exception), warningMessage(exception));
            return CompletableFuture.failedFuture(exception);
        }

        notifySpeechStarted();
        rememberActiveFuture(requestId, speechFuture);
        return speechFuture;
    }

    private CompletableFuture<Void> startWarmUp() {
        if (speechService == null || voiceProfileRegistry == null) {
            return CompletableFuture.completedFuture(null);
        }

        VoiceProfile neutralProfile = voiceProfileRegistry.get(SpeechEmotion.NEUTRAL);
        if (neutralProfile == null) {
            return CompletableFuture.failedFuture(new VoiceSynthesisException("neutral 语音 profile 不可用"));
        }

        return speechService.warmUp(neutralProfile)
                .whenComplete((unused, error) -> {
                    if (error != null && !isCancellation(error)) {
                        warnOnce("warmup", "自动朗读预热失败，第一次朗读可能会稍慢。文字聊天不受影响。");
                    }
                });
    }

    public void stop() {
        synchronized (requestLock) {
            synchronized (this) {
                requestGeneration++;
                activeSpeechFuture = null;
                activeSpeechOwner = null;
            }

            if (speechService != null) {
                speechService.stop();
            }
            notifySpeechFinished();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private long startNewRequest(Object owner) {
        long requestId;
        synchronized (this) {
            requestGeneration++;
            requestId = requestGeneration;
            activeSpeechFuture = null;
            activeSpeechOwner = owner;
        }

        speechService.stop();
        return requestId;
    }

    private void rememberActiveFuture(long requestId, CompletableFuture<Void> speechFuture) {
        synchronized (this) {
            if (requestGeneration != requestId) {
                return;
            }
            activeSpeechFuture = speechFuture;
        }

        speechFuture.whenComplete((unused, error) -> handleSpeechComplete(requestId, error));
    }

    private void handleSpeechComplete(long requestId, Throwable error) {
        synchronized (this) {
            if (requestGeneration != requestId) {
                return;
            }
            activeSpeechFuture = null;
            activeSpeechOwner = null;
        }

        notifySpeechFinished();

        if (error == null) {
            clearLastWarning();
            return;
        }
        if (isCancellation(error)) {
            return;
        }

        warnOnce(errorKey(error), warningMessage(error));
    }

    private void notifySpeechStarted() {
        SpeechLifecycleListener listener;
        synchronized (this) {
            if (speechActive) {
                return;
            }

            speechActive = true;
            listener = speechLifecycleListener;
        }

        runSpeechLifecycleCallback(listener::onSpeechStarted);
    }

    private void notifySpeechFinished() {
        SpeechLifecycleListener listener;
        synchronized (this) {
            if (!speechActive) {
                return;
            }

            speechActive = false;
            listener = speechLifecycleListener;
        }

        runSpeechLifecycleCallback(listener::onSpeechFinished);
    }

    private void runSpeechLifecycleCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException exception) {
            System.out.println("语音状态回调失败：" + exception.getMessage());
        }
    }

    private synchronized void warnOnce(String key, String message) {
        if (Objects.equals(lastWarningKey, key)) {
            return;
        }

        lastWarningKey = key;
        warningSink.accept(message);
    }

    private synchronized void clearLastWarning() {
        lastWarningKey = null;
    }

    private String errorKey(Throwable error) {
        String message = safeMessage(error);
        if (hostedVoice) return "fish:" + message;
        if (message.contains("本地语音配置不存在") || message.contains("缺少 GPT-SoVITS 配置")) {
            return "config";
        }
        if (message.contains("参考音频不存在")) {
            return "reference-audio";
        }
        if (message.contains("/set_gpt_weights") || message.contains("/set_sovits_weights")) {
            return "model-init";
        }
        if (containsCause(error, ConnectException.class) || containsCause(error, HttpConnectTimeoutException.class)) {
            return "service-unavailable";
        }
        if (containsCause(error, HttpTimeoutException.class)) {
            return "timeout";
        }
        if (message.contains("HTTP") || message.contains("语音合成失败")) {
            return "tts";
        }

        return unwrap(error).getClass().getName();
    }

    private String warningMessage(Throwable error) {
        String message = safeMessage(error);
        if (hostedVoice) {
            return "自动朗读失败：" + message + "。文字聊天不受影响。";
        }
        if (message.contains("本地语音配置不存在") || message.contains("缺少 GPT-SoVITS 配置")) {
            return "自动朗读不可用：本地语音配置不存在或不完整。文字聊天不受影响。";
        }
        if (message.contains("参考音频不存在")) {
            return "自动朗读跳过：参考音频不存在。文字聊天不受影响。";
        }
        if (message.contains("/set_gpt_weights") || message.contains("/set_sovits_weights")) {
            return "自动朗读失败：GPT-SoVITS 模型初始化失败。文字聊天不受影响。";
        }
        if (containsCause(error, ConnectException.class) || containsCause(error, HttpConnectTimeoutException.class)) {
            return "自动朗读失败：GPT-SoVITS API 未启动或无法连接。文字聊天不受影响。";
        }
        if (containsCause(error, HttpTimeoutException.class)) {
            return "自动朗读失败：GPT-SoVITS 合成超时。文字聊天不受影响。";
        }
        if (message.contains("HTTP") || message.contains("语音合成失败")) {
            return "自动朗读失败：TTS 请求失败。文字聊天不受影响。";
        }

        return "自动朗读失败：" + message + "。文字聊天不受影响。";
    }

    private boolean containsCause(Throwable error, Class<? extends Throwable> causeType) {
        Throwable current = error;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    private boolean isCancellation(Throwable error) {
        return containsCause(error, CancellationException.class);
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "";
        }

        Throwable unwrapped = unwrap(error);
        String message = unwrapped.getMessage();
        if (message == null || message.isBlank()) {
            return unwrapped.getClass().getSimpleName();
        }

        return message;
    }
}
