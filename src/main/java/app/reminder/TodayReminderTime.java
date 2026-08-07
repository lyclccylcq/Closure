package app.reminder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public final class TodayReminderTime {
    private TodayReminderTime() {
    }

    public static LocalDateTime atToday(int hour, int minute) {
        return atToday(hour, minute, Clock.systemDefaultZone());
    }

    static LocalDateTime atToday(int hour, int minute, Clock clock) {
        requireValidTime(hour, minute);
        Objects.requireNonNull(clock, "clock");
        return LocalDate.now(clock).atTime(hour, minute);
    }

    private static void requireValidTime(int hour, int minute) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("小时必须在 0 到 23 之间");
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("分钟必须在 0 到 59 之间");
        }
    }
}
