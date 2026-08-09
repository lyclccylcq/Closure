package app.reminder;

import app.ai.AiChatRequest;
import app.ai.AiModelClient;
import app.ai.AiStreamListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCourseScheduleImportConverterTest {
    @TempDir
    Path tempDir;

    @Test
    void streamsAiConvertedCsvLines() throws Exception {
        Path source = tempDir.resolve("messy-schedule.txt");
        Files.writeString(source, "算法课：2026年8月7日 11:50-13:00，C101");
        RecordingAiModelClient aiClient = new RecordingAiModelClient("""
                ```csv
                title,start,end,type,remindBeforeMinutes,location,notes
                算法课,2026-08-07 11:50,2026-08-07 13:00,class,15,C101,
                ```
                """);
        AiCourseScheduleImportConverter converter = new AiCourseScheduleImportConverter(aiClient);

        List<String> convertedLines = converter.convertToScheduleLines(source, "没有找到可识别的课程时间").join();
        List<CourseScheduleEntry> entries = new CourseScheduleParser().parseLines(convertedLines);

        assertEquals("title,start,end,type,remindBeforeMinutes,location,notes", convertedLines.getFirst());
        assertEquals(1, entries.size());
        assertEquals("算法课", entries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 11, 50), entries.getFirst().startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 13, 0), entries.getFirst().endsAt());
        assertEquals(0.0, aiClient.request.getTemperature());
        assertTrue(aiClient.request.getMessages().getFirst().getContent().contains("课程表格式转换器"));
        assertTrue(aiClient.request.getMessages().getLast().getContent().contains("算法课"));
    }

    @Test
    void addsHeaderWhenAiReturnsOnlyRows() {
        List<String> convertedLines = AiCourseScheduleImportConverter.normalizeCsvLines(
                "算法课,2026-08-07 11:50,2026-08-07 13:00,class,15,C101,"
        );

        assertEquals("title,start,end,type,remindBeforeMinutes,location,notes", convertedLines.getFirst());
        assertEquals(2, convertedLines.size());
    }

    private static final class RecordingAiModelClient implements AiModelClient {
        private final String answer;
        private AiChatRequest request;

        private RecordingAiModelClient(String answer) {
            this.answer = answer;
        }

        @Override
        public void streamChat(AiChatRequest request, AiStreamListener listener) {
            this.request = request;
            answer.codePoints()
                    .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                    .forEach(listener::onToken);
            listener.onComplete();
        }
    }
}
