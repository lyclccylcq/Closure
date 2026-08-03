package app.reminder;

import java.util.Locale;

public enum CourseReminderType {
    CLASS("上课"),
    DEADLINE("Deadline");

    private final String displayName;

    CourseReminderType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static CourseReminderType fromText(String text) {
        if (text == null || text.isBlank()) {
            return CLASS;
        }

        String normalizedText = text.trim().toLowerCase(Locale.ROOT);
        if (normalizedText.contains("deadline")
                || normalizedText.contains("due")
                || normalizedText.contains("ddl")
                || normalizedText.contains("截止")) {
            return DEADLINE;
        }

        return switch (normalizedText) {
            case "deadline", "due", "作业", "截止", "截止日期", "ddl", "due date" -> DEADLINE;
            default -> CLASS;
        };
    }
}
