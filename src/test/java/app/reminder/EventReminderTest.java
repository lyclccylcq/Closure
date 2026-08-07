package app.reminder;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventReminderTest {
    @Test
    void findsEntriesInsideReminderWindow() {
        EventReminderEntry entry = new EventReminderEntry(
                "开组会",
                LocalDateTime.of(2026, 8, 5, 14, 30),
                "博士，组会时间到了。"
        );
        EventReminder reminder = new EventReminder(List.of(entry), firedEntry -> {
        });

        assertTrue(reminder.dueEntries(LocalDateTime.of(2026, 8, 5, 14, 29)).isEmpty());
        assertEquals(List.of(entry), reminder.dueEntries(LocalDateTime.of(2026, 8, 5, 14, 30)));
        assertEquals(List.of(entry), reminder.dueEntries(LocalDateTime.of(2026, 8, 5, 14, 30, 59)));
        assertTrue(reminder.dueEntries(LocalDateTime.of(2026, 8, 5, 14, 31)).isEmpty());
    }

    @Test
    void checkNowFiresDueEventOnlyOnce() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-05T04:30:00Z"),
                ZoneId.of("Australia/Sydney")
        );
        EventReminderEntry entry = new EventReminderEntry(
                "提交作业",
                LocalDateTime.of(2026, 8, 5, 14, 30),
                "博士，作业提交时间到了，该处理一下了。"
        );
        List<EventReminderEntry> firedEntries = new ArrayList<>();
        EventReminder reminder = new EventReminder(List.of(entry), clock, firedEntries::add);

        reminder.checkNow();
        reminder.checkNow();

        assertEquals(List.of(entry), firedEntries);
    }
}
