package app.service;

import app.chat.model.AssistantReply;
import app.voice.GptSovitsClient;
import app.voice.GptSovitsConfig;
import app.voice.VoiceProfileRegistry;
import app.voice.VoiceSynthesisException;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
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

    private CompletableFuture<Void> activeSpeechFuture;
    private CompletableFuture<Void> readyForSpeechFuture;
    private String lastWarningKey;
    private boolean autoSpeakEnabled;
    private long requestGeneration;

    public ChatSpeechController(
            AssistantSpeechService speechService,
            VoiceProfileRegistry voiceProfileRegistry,
            boolean autoSpeakEnabled,
            Consumer<String> warningSink
    ) {
        this.speechService = Objects.requireNonNull(speechService, "speechService");
        this.voiceProfileRegistry = Objects.requireNonNull(voiceProfileRegistry, "voiceProfileRegistry");
        this.autoSpeakEnabled = autoSpeakEnabled;
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        unavailableCause = null;
        readyForSpeechFuture = startWarmUp();
    }

    private ChatSpeechController(boolean autoSpeakEnabled, Consumer<String> warningSink, Throwable unavailableCause) {
        speechService = null;
        voiceProfileRegistry = null;
        this.autoSpeakEnabled = autoSpeakEnabled;
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        this.unavailableCause = Objects.requireNonNull(unavailableCause, "unavailableCause");
        readyForSpeechFuture = CompletableFuture.failedFuture(unavailableCause);
    }

    public static ChatSpeechController fromEnvironment() {
        return fromEnvironment(message -> System.out.println("自动朗读警告：" + message));
    }

    public static ChatSpeechController fromEnvironment(Consumer<String> warningSink) {
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
                            new AudioPlayerService()
                    ),
                    voiceProfileRegistry,
                    config.isAutoSpeakEnabled(),
                    warningSink
            );
        } catch (RuntimeException exception) {
            return unavailable(autoSpeakEnabled, warningSink, exception);
        }
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

    public void speakAssistantReply(String replyText) {
        speakText(replyText);
    }

    public void speakAssistantReply(AssistantReply assistantReply) {
        if (assistantReply != null) {
            speakWithEmotion(assistantReply.getSpeechText(), assistantReply.getDominantEmotion());
        }
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

    public CompletableFuture<Void> speakFullTextAsync(String text) {
        String speechText = SpeechTextSanitizer.sanitizeFull(text);
        if (!speechText.isBlank()) {
            return speakWithEmotionAsync(speechText, SpeechEmotion.NEUTRAL);
        }

        return CompletableFuture.completedFuture(null);
    }

    public void speakWithEmotion(String speechText, SpeechEmotion emotion) {
        speakWithEmotionAsync(speechText, emotion);
    }

    public CompletableFuture<Void> speakWithEmotionAsync(String speechText, SpeechEmotion emotion) {
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

        VoiceProfile profile = voiceProfileRegistry.get(emotion);
        if (profile == null) {
            return CompletableFuture.completedFuture(null);
        }

        long requestId = startNewRequest();
        CompletableFuture<Void> speechFuture;
        try {
            speechFuture = speechService.speak(speechText, profile);
        } catch (RuntimeException exception) {
            warnOnce(errorKey(exception), warningMessage(exception));
            return CompletableFuture.failedFuture(exception);
        }

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
        CompletableFuture<Void> speechFuture;
        synchronized (this) {
            requestGeneration++;
            speechFuture = activeSpeechFuture;
            activeSpeechFuture = null;
        }

        if (speechService != null) {
            speechService.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private long startNewRequest() {
        CompletableFuture<Void> previousFuture;
        long requestId;
        synchronized (this) {
            requestGeneration++;
            requestId = requestGeneration;
            previousFuture = activeSpeechFuture;
            activeSpeechFuture = null;
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
        }

        if (error == null) {
            clearLastWarning();
            return;
        }
        if (isCancellation(error)) {
            return;
        }

        warnOnce(errorKey(error), warningMessage(error));
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
