package app.reminder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

public record CourseScheduleEntry(
        String title,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        CourseReminderType type,
        Duration remindBefore,
        String location,
        String notes
) {
    public CourseScheduleEntry {
        title = requireText(title, "title");
        startsAt = Objects.requireNonNull(startsAt, "startsAt");
        type = Objects.requireNonNullElse(type, CourseReminderType.CLASS);
        remindBefore = Objects.requireNonNullElse(remindBefore, defaultRemindBefore(type));
        location = normalizeOptionalText(location);
        notes = normalizeOptionalText(notes);

        if (remindBefore.isNegative()) {
            throw new IllegalArgumentException("remindBefore must not be negative");
        }
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt must not be before startsAt");
        }
    }

    public static Duration defaultRemindBefore(CourseReminderType type) {
        if (type == CourseReminderType.DEADLINE) {
            return Duration.ofHours(24);
        }

        return Duration.ofMinutes(15);
    }

    public String stableKey() {
        return type.name() + "|" + startsAt + "|" + title + "|" + location;
    }

    private static String requireText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return text.trim();
    }

    private static String normalizeOptionalText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text.trim();
    }
}
