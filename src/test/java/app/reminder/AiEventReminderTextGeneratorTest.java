package app.reminder;

import app.ai.AiChatRequest;
import app.ai.AiModelClient;
import app.ai.AiStreamListener;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiEventReminderTextGeneratorTest {
    @Test
    void streamsAiGeneratedReminderTextForEvent() {
        RecordingAiModelClient aiClient = new RecordingAiModelClient(
                "“博士，作业提交时间到了，终端这边已经替你盯住了。”",
                null
        );
        AiEventReminderTextGenerator generator = new AiEventReminderTextGenerator(aiClient);

        String reminderText = generator.generateReminderText(
                "提交 COMP6710 作业",
                LocalDateTime.of(2026, 8, 5, 14, 30)
        ).join();

        assertEquals("博士，作业提交时间到了，终端这边已经替你盯住了。", reminderText);
        assertEquals(0.85, aiClient.request.getTemperature());
        assertTrue(aiClient.request.getMessages().getFirst().getContent().contains("可露希尔"));
        assertTrue(aiClient.request.getMessages().getLast().getContent().contains("提交 COMP6710 作业"));
        assertTrue(aiClient.request.getMessages().getLast().getContent().contains("2026年8月5日 14:30"));
    }

    @Test
    void fallsBackWhenAiGenerationFails() {
        RecordingAiModelClient aiClient = new RecordingAiModelClient(
                "",
                new RuntimeException("offline")
        );
        AiEventReminderTextGenerator generator = new AiEventReminderTextGenerator(aiClient);

        String reminderText = generator.generateReminderText(
                "交报告",
                LocalDateTime.of(2026, 8, 5, 18, 0)
        ).join();

        assertEquals("博士，提醒时间到了：交报告，该处理一下了。", reminderText);
    }

    private static final class RecordingAiModelClient implements AiModelClient {
        private final String answer;
        private final Throwable error;
        private AiChatRequest request;

        private RecordingAiModelClient(String answer, Throwable error) {
            this.answer = answer;
            this.error = error;
        }

        @Override
        public void streamChat(AiChatRequest request, AiStreamListener listener) {
            this.request = request;
            if (error != null) {
                listener.onError(error);
                return;
            }

            answer.codePoints()
                    .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                    .forEach(listener::onToken);
            listener.onComplete();
        }
    }
}
