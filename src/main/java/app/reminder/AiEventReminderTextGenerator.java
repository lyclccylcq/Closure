package app.reminder;

import app.ai.AiChatRequest;
import app.ai.AiModelClient;
import app.ai.AiModelConfig;
import app.ai.AiStreamListener;
import app.ai.OpenAiCompatibleChatClient;
import app.service.SpeechTextSanitizer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiEventReminderTextGenerator implements EventReminderTextGenerator {
    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final String SYSTEM_PROMPT = """
            你现在扮演《明日方舟》中的可露希尔。

            这是桌面事件提醒模块。用户会输入一个事件和提醒时间，你需要生成到点出现并朗读的一句话。

            要求：
            - 明确提醒博士现在该处理这个事件。
            - 语气自然、简洁、生活化，像熟悉的人顺口提醒。
            - 可以有轻微可露希尔角色感，但不要刻意卖萌，不要夸张玩笑。
            - 不要像客服、日程软件或 AI 助手。
            - 不要编造事件外的信息。
            - 控制在 25 至 60 个汉字左右。
            - 只输出最终提醒内容，不要解释，不要 JSON，不要 Markdown，不要引号。
            """;

    private final AiModelClient aiClient;

    public AiEventReminderTextGenerator(AiModelClient aiClient) {
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient");
    }

    public static EventReminderTextGenerator fromEnvironment() {
        try {
            return new AiEventReminderTextGenerator(new OpenAiCompatibleChatClient(AiModelConfig.fromEnvironment()));
        } catch (RuntimeException exception) {
            System.out.println("事件提醒 AI 配置不可用，使用本地提醒文案：" + exception.getMessage());
            return new FallbackEventReminderTextGenerator();
        }
    }

    @Override
    public CompletableFuture<String> generateReminderText(String eventText, LocalDateTime remindAt) {
        String normalizedEventText = requireEventText(eventText);
        LocalDateTime normalizedRemindAt = Objects.requireNonNull(remindAt, "remindAt");
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        StringBuilder streamedText = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean(false);

        AiChatRequest request = AiChatRequest
                .singleTurn(SYSTEM_PROMPT, userPrompt(normalizedEventText, normalizedRemindAt))
                .withTemperature(0.85);

        try {
            aiClient.streamChat(request, new AiStreamListener() {
                @Override
                public void onToken(String token) {
                    streamedText.append(token);
                }

                @Override
                public void onComplete() {
                    if (completed.compareAndSet(false, true)) {
                        resultFuture.complete(normalizeReminderText(
                                streamedText.toString(),
                                normalizedEventText,
                                normalizedRemindAt
                        ));
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (completed.compareAndSet(false, true)) {
                        System.out.println("事件提醒 AI 生成失败，使用本地提醒文案：" + error.getMessage());
                        resultFuture.complete(fallbackReminderText(normalizedEventText));
                    }
                }
            });
        } catch (RuntimeException exception) {
            if (completed.compareAndSet(false, true)) {
                resultFuture.complete(fallbackReminderText(normalizedEventText));
            }
        }

        return resultFuture;
    }

    @Override
    public void close() {
        aiClient.close();
    }

    private static String userPrompt(String eventText, LocalDateTime remindAt) {
        return """
                事件：%s
                提醒时间：%s

                请生成提醒语。
                """.formatted(eventText, remindAt.format(DISPLAY_TIME_FORMATTER));
    }

    private static String normalizeReminderText(String rawText, String eventText, LocalDateTime remindAt) {
        String cleanedText = SpeechTextSanitizer.sanitizeFull(rawText)
                .replace('\n', ' ')
                .trim();
        cleanedText = stripWrappingQuotes(cleanedText);
        if (cleanedText.isBlank()) {
            return fallbackReminderText(eventText);
        }

        return cleanedText;
    }

    static String fallbackReminderText(String eventText) {
        String cleanedEventText = SpeechTextSanitizer.sanitizeSegment(requireEventText(eventText), 40);
        if (cleanedEventText.isBlank()) {
            cleanedEventText = "这件事";
        }

        return "博士，提醒时间到了：" + cleanedEventText + "，该处理一下了。";
    }

    private static String stripWrappingQuotes(String text) {
        String cleanedText = text;
        while (cleanedText.length() >= 2 && isOpeningQuote(cleanedText.charAt(0))) {
            cleanedText = cleanedText.substring(1).trim();
        }
        while (cleanedText.length() >= 2 && isClosingQuote(cleanedText.charAt(cleanedText.length() - 1))) {
            cleanedText = cleanedText.substring(0, cleanedText.length() - 1).trim();
        }

        return cleanedText;
    }

    private static boolean isOpeningQuote(char character) {
        return character == '"' || character == '\'' || character == '“' || character == '「' || character == '『';
    }

    private static boolean isClosingQuote(char character) {
        return character == '"' || character == '\'' || character == '”' || character == '」' || character == '』';
    }

    private static String requireEventText(String eventText) {
        if (eventText == null || eventText.isBlank()) {
            throw new IllegalArgumentException("事件内容不能为空");
        }

        return eventText.trim();
    }

    private static final class FallbackEventReminderTextGenerator implements EventReminderTextGenerator {
        @Override
        public CompletableFuture<String> generateReminderText(String eventText, LocalDateTime remindAt) {
            Objects.requireNonNull(remindAt, "remindAt");
            return CompletableFuture.completedFuture(fallbackReminderText(eventText));
        }
    }
}
