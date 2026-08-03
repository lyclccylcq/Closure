package app.reminder;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CourseScheduleParserTest {
    private final CourseScheduleParser parser = new CourseScheduleParser();

    @Test
    void parsesCsvScheduleWithChineseHeaders() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "课程,日期,开始时间,结束时间,类型,提前,地点,备注",
                "高等数学,2026-08-03,09:00,10:30,class,30m,A101,带教材",
                "论文初稿,2026-08-10,23:59,,deadline,2h,,提交到 Moodle"
        ));

        assertEquals(2, entries.size());
        assertEquals("高等数学", entries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 0), entries.getFirst().startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 3, 10, 30), entries.getFirst().endsAt());
        assertEquals(Duration.ofMinutes(30), entries.getFirst().remindBefore());
        assertEquals("A101", entries.getFirst().location());

        CourseScheduleEntry deadline = entries.get(1);
        assertEquals(CourseReminderType.DEADLINE, deadline.type());
        assertEquals(Duration.ofHours(2), deadline.remindBefore());
    }

    @Test
    void parsesSimpleTextSchedule() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "2026-08-04 14:00 | 数据结构 | class | 15 | B203"
        ));

        assertEquals(1, entries.size());
        assertEquals("数据结构", entries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 4, 14, 0), entries.getFirst().startsAt());
        assertEquals(Duration.ofMinutes(15), entries.getFirst().remindBefore());
        assertEquals("B203", entries.getFirst().location());
    }

    @Test
    void parsesIcsSchedule() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "BEGIN:VCALENDAR",
                "BEGIN:VEVENT",
                "SUMMARY:Computer Systems",
                "DTSTART:20260805T090000",
                "DTEND:20260805T110000",
                "LOCATION:Lab 2",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "SUMMARY:Assignment deadline",
                "DTSTART:20260812T235900",
                "END:VEVENT",
                "END:VCALENDAR"
        ));

        assertEquals(2, entries.size());
        assertEquals("Computer Systems", entries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 5, 9, 0), entries.getFirst().startsAt());
        assertEquals("Lab 2", entries.getFirst().location());
        assertEquals(CourseReminderType.DEADLINE, entries.get(1).type());
        assertEquals(Duration.ofHours(24), entries.get(1).remindBefore());
    }
}
