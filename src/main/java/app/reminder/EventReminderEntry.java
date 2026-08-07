package app.reminder;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.stream.Collectors;

public record EventReminderEntry(
        String eventText,
        LocalDateTime remindAt,
        String reminderText
) {
    public EventReminderEntry {
        eventText = requireText(eventText, "eventText");
        remindAt = Objects.requireNonNull(remindAt, "remindAt");
        reminderText = requireText(reminderText, "reminderText");
    }

    public String stableKey() {
        return remindAt + "|" + eventText + "|" + reminderText;
    }

    private static String requireText(String text, String fieldName) {
        if (text == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        String normalizedText = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining(" "))
                .trim();
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return normalizedText;
    }
}
