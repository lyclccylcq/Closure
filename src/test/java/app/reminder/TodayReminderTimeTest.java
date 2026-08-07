package app.reminder;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TodayReminderTimeTest {
    @Test
    void buildsReminderTimeForTheCurrentDay() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-05T22:30:00Z"),
                ZoneId.of("Australia/Sydney")
        );

        assertEquals(
                LocalDateTime.of(2026, 8, 6, 14, 30),
                TodayReminderTime.atToday(14, 30, clock)
        );
    }

    @Test
    void rejectsInvalidClockTimeValues() {
        Clock clock = Clock.systemDefaultZone();

        assertThrows(IllegalArgumentException.class, () -> TodayReminderTime.atToday(-1, 30, clock));
        assertThrows(IllegalArgumentException.class, () -> TodayReminderTime.atToday(24, 30, clock));
        assertThrows(IllegalArgumentException.class, () -> TodayReminderTime.atToday(12, -1, clock));
        assertThrows(IllegalArgumentException.class, () -> TodayReminderTime.atToday(12, 60, clock));
    }
}
