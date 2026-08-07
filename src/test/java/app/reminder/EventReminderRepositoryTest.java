package app.reminder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventReminderRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void savesLoadsAndSortsEventReminders() throws Exception {
        Path remindersPath = tempDir.resolve("event-reminders.csv");
        EventReminderRepository repository = new EventReminderRepository(remindersPath);
        EventReminderEntry laterEntry = new EventReminderEntry(
                "提交 \"COMP6710, A1\"\n检查上传状态",
                LocalDateTime.of(2026, 8, 6, 10, 0),
                "博士，COMP6710 A1 到点了，该提交了。"
        );
        EventReminderEntry earlierEntry = new EventReminderEntry(
                "开组会",
                LocalDateTime.of(2026, 8, 5, 14, 30),
                "博士，组会时间到了，资料带上，别让终端白忙。"
        );

        repository.save(List.of(laterEntry, earlierEntry));
        List<EventReminderEntry> loadedEntries = repository.load();

        assertTrue(Files.isRegularFile(remindersPath));
        assertEquals(List.of(earlierEntry, laterEntry), loadedEntries);
        assertEquals(List.of(laterEntry), repository.loadForDate(LocalDate.of(2026, 8, 6)));
    }

    @Test
    void addsAndRemovesOneShotEventReminder() throws Exception {
        EventReminderRepository repository = new EventReminderRepository(tempDir.resolve("event-reminders.csv"));
        EventReminderEntry entry = new EventReminderEntry(
                "交报告",
                LocalDateTime.of(2026, 8, 5, 18, 0),
                "博士，报告该交了，先把这件事处理掉。"
        );

        assertEquals(List.of(entry), repository.add(entry));
        assertEquals(List.of(), repository.remove(entry));
        assertEquals(List.of(), repository.load());
    }

    @Test
    void addKeepsOnlyTheSameDayManualReminders() throws Exception {
        EventReminderRepository repository = new EventReminderRepository(tempDir.resolve("event-reminders.csv"));
        EventReminderEntry yesterdayEntry = new EventReminderEntry(
                "昨天的提醒",
                LocalDateTime.of(2026, 8, 5, 18, 0),
                "博士，昨天的提醒不该混进今天。"
        );
        EventReminderEntry todayEntry = new EventReminderEntry(
                "今天开会",
                LocalDateTime.of(2026, 8, 6, 10, 30),
                "博士，今天开会时间到了。"
        );

        repository.save(List.of(yesterdayEntry));

        assertEquals(List.of(todayEntry), repository.add(todayEntry));
        assertEquals(List.of(todayEntry), repository.load());
    }
}
