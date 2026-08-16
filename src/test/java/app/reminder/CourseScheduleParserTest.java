package app.reminder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void parsesFlexibleScheduleWithDateAndTimeAwayFromFirstColumn() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "算法课,2026-08-07,11:50-13:00,C101,带电脑"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("算法课", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 11, 50), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 13, 0), entry.endsAt());
        assertEquals("C101", entry.location());
        assertEquals("带电脑", entry.notes());
    }

    @Test
    void parsesUnpaddedAndIsoSimpleTextScheduleTimes() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "2026-8-4 9:00 | 数据结构 | class | 15 | B203",
                "2026-08-05T14:30:00 | Lab due | deadline | 2h"
        ));

        assertEquals(2, entries.size());
        assertEquals(LocalDateTime.of(2026, 8, 4, 9, 0), entries.getFirst().startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 5, 14, 30), entries.get(1).startsAt());
        assertEquals(CourseReminderType.DEADLINE, entries.get(1).type());
    }

    @Test
    void parsesCsvScheduleWithBomAndCalendarExportHeaders() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "\uFEFFSubject,Start Date,Start Time,End Date,End Time,Location,Description",
                "\"COMP6710 Lecture\",7/8/2026,9:00 AM,7/8/2026,10:30 AM,\"Room A\",\"Bring laptop\""
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("COMP6710 Lecture", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 9, 0), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 10, 30), entry.endsAt());
        assertEquals("Room A", entry.location());
        assertEquals("Bring laptop", entry.notes());
    }

    @Test
    void skipsExporterPreambleAndParsesHeaderBelow() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "Downloaded from timetable export",
                "sep=,",
                "课程表",
                "课程,日期,时间,地点",
                "算法课,2026-08-07,11:50 - 13:00,C101"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("算法课", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 11, 50), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 13, 0), entry.endsAt());
        assertEquals("C101", entry.location());
    }

    @Test
    void parsesStartColumnWithDateTimeRange() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "Title,Start,Location",
                "算法课,2026-08-07 11:50-13:00,C101"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("算法课", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 11, 50), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 13, 0), entry.endsAt());
        assertEquals("C101", entry.location());
    }

    @Test
    void parsesScheduleWithChineseHeaderAliasesAndChineseCommaDelimiter() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "名称，开始日期，开始时间，结束日期，结束时间，教室，说明",
                "算法课，2026-08-07，11:50，2026-08-07，13:00，C101，带电脑"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("算法课", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 11, 50), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 13, 0), entry.endsAt());
        assertEquals("C101", entry.location());
        assertEquals("带电脑", entry.notes());
    }

    @Test
    void parsesScheduleWithSemicolonDelimiterAndEventTitleHeader() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "Event Title;Start Date;Start Time;End Date;End Time;Venue;Details",
                "Project demo;2026-8-7;11:50;2026-8-7;12:30;Room 4;slides"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("Project demo", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 11, 50), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 12, 30), entry.endsAt());
        assertEquals("Room 4", entry.location());
        assertEquals("slides", entry.notes());
    }

    @Test
    void parsesTwoDigitAustralianDates() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "Subject,Start Date,Start Time,Location",
                "COMP6710 Lecture,7/8/26,9:00AM,Room A"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals(LocalDateTime.of(2026, 8, 7, 9, 0), entry.startsAt());
        assertEquals("Room A", entry.location());
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

    @Test
    void parsesIcsScheduleWithBom() {
        String timeZone = ZoneId.systemDefault().getId();
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "\uFEFFBEGIN:VCALENDAR",
                "BEGIN:VEVENT",
                "SUMMARY:Computer Systems",
                "DTSTART;TZID=" + timeZone + ":20260805T090000",
                "END:VEVENT",
                "END:VCALENDAR"
        ));

        assertEquals(1, entries.size());
        assertEquals("Computer Systems", entries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 5, 9, 0), entries.getFirst().startsAt());
    }

    @Test
    void parsesStandardIcsPropertiesWithEscapesFoldingAndDuration() {
        String timeZone = ZoneId.systemDefault().getId();
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Closure Test//Calendar//EN",
                "CALSCALE:GREGORIAN",
                "BEGIN:VEVENT",
                "UID:comp6710@example.test",
                "SUMMARY;LANGUAGE=en:COMP6710\\, Lect",
                " ure",
                "DTSTART;TZID=" + timeZone + ":20260805T090000",
                "DURATION:PT1H30M",
                "LOCATION:Room\\, A",
                "DESCRIPTION:Bring lap",
                " top\\nRead chapter 2",
                "END:VEVENT",
                "END:VCALENDAR"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("COMP6710, Lecture", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 5, 9, 0), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 5, 10, 30), entry.endsAt());
        assertEquals("Room, A", entry.location());
        assertEquals("Bring laptop\nRead chapter 2", entry.notes());
    }

    @Test
    void parsesIcsAllDayDateEvents() {
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "BEGIN:VEVENT",
                "SUMMARY:Assignment due",
                "DTSTART;VALUE=DATE:20260812",
                "DTEND;VALUE=DATE:20260813",
                "END:VEVENT",
                "END:VCALENDAR"
        ));

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals(LocalDateTime.of(2026, 8, 12, 0, 0), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 13, 0, 0), entry.endsAt());
        assertEquals(CourseReminderType.DEADLINE, entry.type());
    }

    @Test
    void expandsWeeklyIcsRecurrenceWithByDayExdateAndRdate() {
        String timeZone = ZoneId.systemDefault().getId();
        List<CourseScheduleEntry> entries = parser.parseLines(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "BEGIN:VEVENT",
                "SUMMARY:Algorithms tutorial",
                "DTSTART;TZID=" + timeZone + ":20260803T090000",
                "DTEND;TZID=" + timeZone + ":20260803T100000",
                "RRULE:FREQ=WEEKLY;COUNT=4;BYDAY=MO,WE",
                "EXDATE;TZID=" + timeZone + ":20260805T090000",
                "RDATE;TZID=" + timeZone + ":20260814T090000",
                "END:VEVENT",
                "END:VCALENDAR"
        ));

        assertEquals(4, entries.size());
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 0), entries.get(0).startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0), entries.get(1).startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 12, 9, 0), entries.get(2).startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 14, 9, 0), entries.get(3).startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 14, 10, 0), entries.get(3).endsAt());
    }

    @Test
    void readsGb18030CsvFiles(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("schedule.csv");
        String text = String.join(System.lineSeparator(),
                "课程,日期,开始时间,地点",
                "高等数学,2026-08-03,09:00,A101"
        );
        Files.write(csv, text.getBytes(Charset.forName("GB18030")));

        List<CourseScheduleEntry> entries = parser.parse(csv);

        assertEquals(1, entries.size());
        assertEquals("高等数学", entries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 3, 9, 0), entries.getFirst().startsAt());
    }

    @Test
    void parsesXlsxScheduleWithSharedStrings(@TempDir Path tempDir) throws IOException {
        Path xlsx = tempDir.resolve("schedule.xlsx");
        writeMinimalXlsx(xlsx);

        List<CourseScheduleEntry> entries = parser.parse(xlsx);

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("COMP6710 Lecture", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 9, 0), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 10, 30), entry.endsAt());
        assertEquals("Room A", entry.location());
    }

    @Test
    void parsesXlsxScheduleWithNumericDateAndTimeCells(@TempDir Path tempDir) throws IOException {
        Path xlsx = tempDir.resolve("numeric-schedule.xlsx");
        writeNumericDateXlsx(xlsx);

        List<CourseScheduleEntry> entries = parser.parse(xlsx);

        assertEquals(1, entries.size());
        CourseScheduleEntry entry = entries.getFirst();
        assertEquals("COMP6710 Lecture", entry.title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 9, 0), entry.startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 10, 30), entry.endsAt());
        assertEquals("Room A", entry.location());
    }

    @Test
    void rejectsCorruptXlsxWithReadableHint(@TempDir Path tempDir) throws IOException {
        Path xlsx = tempDir.resolve("schedule.xlsx");
        Files.writeString(xlsx, "placeholder");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parser.parse(xlsx));

        assertTrue(exception.getMessage().contains("无法读取"));
    }

    private static void writeMinimalXlsx(Path path) throws IOException {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(path))) {
            writeZipEntry(zipOutputStream, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                    </Types>
                    """);
            writeZipEntry(zipOutputStream, "xl/sharedStrings.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si><t>Subject</t></si>
                      <si><t>Start Date</t></si>
                      <si><t>Start Time</t></si>
                      <si><t>End Date</t></si>
                      <si><t>End Time</t></si>
                      <si><t>Location</t></si>
                      <si><t>COMP6710 Lecture</t></si>
                      <si><t>7/8/2026</t></si>
                      <si><t>9:00 AM</t></si>
                      <si><t>10:30 AM</t></si>
                      <si><t>Room A</t></si>
                    </sst>
                    """);
            writeZipEntry(zipOutputStream, "xl/worksheets/sheet1.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="s"><v>0</v></c>
                          <c r="B1" t="s"><v>1</v></c>
                          <c r="C1" t="s"><v>2</v></c>
                          <c r="D1" t="s"><v>3</v></c>
                          <c r="E1" t="s"><v>4</v></c>
                          <c r="F1" t="s"><v>5</v></c>
                        </row>
                        <row r="2">
                          <c r="A2" t="s"><v>6</v></c>
                          <c r="B2" t="s"><v>7</v></c>
                          <c r="C2" t="s"><v>8</v></c>
                          <c r="D2" t="s"><v>7</v></c>
                          <c r="E2" t="s"><v>9</v></c>
                          <c r="F2" t="s"><v>10</v></c>
                        </row>
                      </sheetData>
                    </worksheet>
                    """);
        }
    }

    private static void writeNumericDateXlsx(Path path) throws IOException {
        String dateSerial = Long.toString(ChronoUnit.DAYS.between(
                LocalDate.of(1899, 12, 30),
                LocalDate.of(2026, 8, 7)
        ));
        String startTimeSerial = String.format(Locale.ROOT, "%.8f", 9.0 / 24.0);
        String endTimeSerial = String.format(Locale.ROOT, "%.8f", 10.5 / 24.0);

        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(path))) {
            writeZipEntry(zipOutputStream, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                    </Types>
                    """);
            writeZipEntry(zipOutputStream, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <cellXfs count="3">
                        <xf numFmtId="0"/>
                        <xf numFmtId="14"/>
                        <xf numFmtId="20"/>
                      </cellXfs>
                    </styleSheet>
                    """);
            writeZipEntry(zipOutputStream, "xl/sharedStrings.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si><t>Subject</t></si>
                      <si><t>Start Date</t></si>
                      <si><t>Start Time</t></si>
                      <si><t>End Date</t></si>
                      <si><t>End Time</t></si>
                      <si><t>Location</t></si>
                      <si><t>COMP6710 Lecture</t></si>
                      <si><t>Room A</t></si>
                    </sst>
                    """);
            writeZipEntry(zipOutputStream, "xl/worksheets/sheet1.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="s"><v>0</v></c>
                          <c r="B1" t="s"><v>1</v></c>
                          <c r="C1" t="s"><v>2</v></c>
                          <c r="D1" t="s"><v>3</v></c>
                          <c r="E1" t="s"><v>4</v></c>
                          <c r="F1" t="s"><v>5</v></c>
                        </row>
                        <row r="2">
                          <c r="A2" t="s"><v>6</v></c>
                          <c r="B2" s="1"><v>%s</v></c>
                          <c r="C2" s="2"><v>%s</v></c>
                          <c r="D2" s="1"><v>%s</v></c>
                          <c r="E2" s="2"><v>%s</v></c>
                          <c r="F2" t="s"><v>7</v></c>
                        </row>
                      </sheetData>
                    </worksheet>
                    """.formatted(dateSerial, startTimeSerial, dateSerial, endTimeSerial));
        }
    }

    private static void writeZipEntry(ZipOutputStream zipOutputStream, String name, String text) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(name));
        zipOutputStream.write(text.getBytes(StandardCharsets.UTF_8));
        zipOutputStream.closeEntry();
    }
}
