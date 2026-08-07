package app.reminder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class EventReminderDateTimeParser {
    private static final DateTimeFormatter[] DATE_TIME_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            DateTimeFormatter.ofPattern("yyyy.M.d H:mm")
    };

    private EventReminderDateTimeParser() {
    }

    static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("提醒时间不能为空");
        }

        String trimmedText = text.trim();
        String normalizedText = trimmedText.replace('T', ' ');
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalizedText, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        try {
            return LocalDateTime.parse(trimmedText, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException("本地提醒时间格式无法识别：" + text);
        }
    }
}
