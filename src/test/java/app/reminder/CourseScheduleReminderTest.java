package app.reminder;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseScheduleReminderTest {
    @Test
    void findsEntriesInsideReminderWindow() {
        CourseScheduleEntry entry = new CourseScheduleEntry(
                "操作系统",
                LocalDateTime.of(2026, 8, 7, 9, 0),
                null,
                CourseReminderType.CLASS,
                Duration.ofMinutes(15),
                "A201",
                ""
        );
        CourseScheduleReminder reminder = new CourseScheduleReminder(List.of(entry), message -> {
        });

        assertTrue(reminder.dueEntries(LocalDateTime.of(2026, 8, 7, 8, 44)).isEmpty());
        assertEquals(List.of(entry), reminder.dueEntries(LocalDateTime.of(2026, 8, 7, 8, 45)));
        assertEquals(List.of(entry), reminder.dueEntries(LocalDateTime.of(2026, 8, 7, 9, 0)));
        assertTrue(reminder.dueEntries(LocalDateTime.of(2026, 8, 7, 9, 1)).isEmpty());
    }

    @Test
    void checkNowFiresDueReminderOnlyOnce() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-07T08:45:00+10:00"),
                ZoneId.of("Australia/Sydney")
        );
        CourseScheduleEntry entry = new CourseScheduleEntry(
                "测试课程",
                LocalDateTime.of(2026, 8, 7, 9, 0),
                null,
                CourseReminderType.CLASS,
                Duration.ofMinutes(15),
                "测试教室",
                ""
        );
        List<String> messages = new ArrayList<>();
        CourseScheduleReminder reminder = new CourseScheduleReminder(List.of(entry), clock, messages::add);

        reminder.checkNow();
        reminder.checkNow();

        assertEquals(1, messages.size());
        assertEquals("博士，上课提醒：测试课程 在 8月7日 09:00，地点：测试教室。", messages.getFirst());
    }
}
