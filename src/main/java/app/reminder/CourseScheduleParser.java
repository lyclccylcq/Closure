package app.reminder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CourseScheduleParser {
    private static final DateTimeFormatter[] DATE_TIME_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            formatter("yyyy-M-d H:mm"),
            formatter("yyyy-M-d H:mm:ss"),
            formatter("yyyy-M-d h:mm a"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            formatter("yyyy/M/d H:mm:ss"),
            formatter("yyyy/M/d h:mm a"),
            DateTimeFormatter.ofPattern("yyyy.M.d H:mm"),
            formatter("yyyy.M.d H:mm:ss"),
            formatter("d/M/yyyy H:mm"),
            formatter("d/M/yyyy H:mm:ss"),
            formatter("d/M/yyyy h:mm a"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            formatter("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            formatter("d/M/yyyy")
    };

    public List<CourseScheduleEntry> parse(Path scheduleFile) throws IOException {
        List<String> lines = Files.readAllLines(scheduleFile, StandardCharsets.UTF_8);
        return parseLines(lines);
    }

    public List<CourseScheduleEntry> parseLines(List<String> lines) {
        List<String> meaningfulLines = lines.stream()
                .map(CourseScheduleParser::stripByteOrderMark)
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        if (meaningfulLines.isEmpty()) {
            return List.of();
        }

        if (meaningfulLines.getFirst().equalsIgnoreCase("BEGIN:VCALENDAR")) {
            return parseIcs(meaningfulLines);
        }

        List<String> firstColumns = splitDelimitedLine(meaningfulLines.getFirst());
        if (looksLikeHeader(firstColumns)) {
            return parseCsv(meaningfulLines);
        }

        return parseSimpleText(meaningfulLines);
    }

    private List<CourseScheduleEntry> parseIcs(List<String> lines) {
        List<CourseScheduleEntry> entries = new ArrayList<>();
        Map<String, String> eventValues = null;

        for (String line : unfoldIcsLines(lines)) {
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                eventValues = new HashMap<>();
                continue;
            }

            if (line.equalsIgnoreCase("END:VEVENT")) {
                if (eventValues != null) {
                    entries.add(entryFromIcsEvent(eventValues, entries.size() + 1));
                }
                eventValues = null;
                continue;
            }

            if (eventValues == null) {
                continue;
            }

            int separatorIndex = line.indexOf(':');
            if (separatorIndex <= 0) {
                continue;
            }

            String name = line.substring(0, separatorIndex);
            String value = line.substring(separatorIndex + 1);
            String key = name.contains(";") ? name.substring(0, name.indexOf(';')) : name;
            eventValues.put(key.toUpperCase(Locale.ROOT), value.trim());
        }

        return entries;
    }

    private CourseScheduleEntry entryFromIcsEvent(Map<String, String> eventValues, int eventNumber) {
        String title = firstNonBlank(eventValues.get("SUMMARY"), "课程表事件 " + eventNumber);
        String description = eventValues.getOrDefault("DESCRIPTION", "");
        CourseReminderType type = CourseReminderType.fromText(title + " " + description);

        return new CourseScheduleEntry(
                title,
                parseIcsDateTime(eventValues.get("DTSTART"), eventNumber),
                parseOptionalIcsDateTime(eventValues.get("DTEND"), eventNumber),
                type,
                CourseScheduleEntry.defaultRemindBefore(type),
                eventValues.getOrDefault("LOCATION", ""),
                description
        );
    }

    private static List<String> unfoldIcsLines(List<String> lines) {
        List<String> unfoldedLines = new ArrayList<>();
        for (String line : lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !unfoldedLines.isEmpty()) {
                int lastIndex = unfoldedLines.size() - 1;
                unfoldedLines.set(lastIndex, unfoldedLines.get(lastIndex) + line.trim());
            } else {
                unfoldedLines.add(line.trim());
            }
        }

        return unfoldedLines;
    }

    private List<CourseScheduleEntry> parseCsv(List<String> lines) {
        List<String> headers = splitDelimitedLine(lines.getFirst());
        Map<String, Integer> headerIndexes = headerIndexes(headers);
        List<CourseScheduleEntry> entries = new ArrayList<>();

        for (int index = 1; index < lines.size(); index++) {
            List<String> columns = splitDelimitedLine(lines.get(index));
            entries.add(entryFromColumns(headerIndexes, columns, index + 1));
        }

        return entries;
    }

    private CourseScheduleEntry entryFromColumns(Map<String, Integer> headerIndexes, List<String> columns, int lineNumber) {
        String title = column(columns, headerIndexes, "title", "course", "name", "subject", "summary",
                "event", "eventTitle", "课程", "课程名称", "标题", "事项", "活动", "名称");
        LocalDateTime startsAt = parseDateTime(
                firstNonBlank(
                        combineDateAndTime(
                                column(columns, headerIndexes, "date", "startDate", "日期", "开始日期"),
                                column(columns, headerIndexes, "startTime", "开始时间", "time", "时间")
                        ),
                        column(columns, headerIndexes, "start", "startsAt", "datetime", "startDate", "开始", "开始日期", "日期")
                ),
                lineNumber
        );
        LocalDateTime endsAt = parseOptionalDateTime(
                firstNonBlank(
                        combineDateAndTime(
                                column(columns, headerIndexes, "endDate", "date", "结束日期", "日期"),
                                column(columns, headerIndexes, "endTime", "结束时间")
                        ),
                        column(columns, headerIndexes, "end", "endsAt", "endDate", "结束", "结束日期")
                ),
                lineNumber
        );
        CourseReminderType type = CourseReminderType.fromText(
                column(columns, headerIndexes, "type", "kind", "category", "类型")
        );
        Duration remindBefore = parseReminderDuration(
                column(columns, headerIndexes, "remindBeforeMinutes", "remind", "reminder", "reminderMinutes",
                        "reminderBefore", "提醒提前分钟", "提前分钟", "提前"),
                type
        );

        return new CourseScheduleEntry(
                title,
                startsAt,
                endsAt,
                type,
                remindBefore,
                column(columns, headerIndexes, "location", "where", "room", "venue", "地点", "教室"),
                column(columns, headerIndexes, "notes", "note", "description", "details", "备注", "说明")
        );
    }

    private List<CourseScheduleEntry> parseSimpleText(List<String> lines) {
        List<CourseScheduleEntry> entries = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            List<String> columns = splitDelimitedLine(lines.get(index));
            if (columns.size() < 2) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 行至少需要时间和标题");
            }

            LocalDateTime startsAt = parseDateTime(columns.get(0), index + 1);
            CourseReminderType type = columns.size() >= 3 ? CourseReminderType.fromText(columns.get(2)) : CourseReminderType.CLASS;
            Duration remindBefore = columns.size() >= 4 ? parseReminderDuration(columns.get(3), type) : CourseScheduleEntry.defaultRemindBefore(type);
            String location = columns.size() >= 5 ? columns.get(4) : "";
            String notes = columns.size() >= 6 ? columns.get(5) : "";
            entries.add(new CourseScheduleEntry(columns.get(1), startsAt, null, type, remindBefore, location, notes));
        }

        return entries;
    }

    private static boolean looksLikeHeader(List<String> columns) {
        if (!columns.isEmpty() && canParseDateTime(columns.getFirst())) {
            return false;
        }

        return columns.stream().map(CourseScheduleParser::normalizeHeader).anyMatch(CourseScheduleParser::isKnownHeader);
    }

    static List<String> splitDelimitedLine(String line) {
        char delimiter = delimiterFor(line);

        List<String> columns = new ArrayList<>();
        StringBuilder currentColumn = new StringBuilder();
        boolean inQuote = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (inQuote && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    currentColumn.append('"');
                    index++;
                } else {
                    inQuote = !inQuote;
                }
                continue;
            }

            if (character == delimiter && !inQuote) {
                columns.add(currentColumn.toString().trim());
                currentColumn.setLength(0);
                continue;
            }

            currentColumn.append(character);
        }

        columns.add(currentColumn.toString().trim());
        return columns;
    }

    private static char delimiterFor(String line) {
        char standardDelimiter = delimiterFor(line, new char[]{'\t', ',', ';', '|'});
        if (countDelimiterOutsideQuotes(line, standardDelimiter) > 0) {
            return standardDelimiter;
        }

        return delimiterFor(line, new char[]{'，', '；'});
    }

    private static char delimiterFor(String line, char[] candidates) {
        char bestDelimiter = candidates[0];
        int bestCount = 0;
        for (char candidate : candidates) {
            int count = countDelimiterOutsideQuotes(line, candidate);
            if (count > bestCount) {
                bestDelimiter = candidate;
                bestCount = count;
            }
        }

        return bestDelimiter;
    }

    private static int countDelimiterOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean inQuote = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (inQuote && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    index++;
                } else {
                    inQuote = !inQuote;
                }
                continue;
            }

            if (character == delimiter && !inQuote) {
                count++;
            }
        }

        return count;
    }

    private static Map<String, Integer> headerIndexes(List<String> headers) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            indexes.put(normalizeHeader(headers.get(index)), index);
        }

        return indexes;
    }

    private static String normalizeHeader(String header) {
        return stripByteOrderMark(header).trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
    }

    private static boolean isKnownHeader(String header) {
        return switch (header) {
            case "title", "course", "coursename", "name", "subject", "summary", "event", "eventtitle",
                 "activity", "module", "unit", "lesson", "class", "课程", "课程名称", "标题", "事项", "活动",
                 "名称", "科目", "主题", "date", "start", "startsat", "datetime", "startdate", "starttime",
                 "end", "endsat", "enddate", "endtime", "time", "日期", "开始", "开始日期", "开始时间",
                 "结束", "结束日期", "结束时间", "时间", "type", "kind", "category", "类型", "location",
                 "where", "room", "venue", "地点", "教室", "notes", "note", "description", "details",
                 "备注", "说明" -> true;
            default -> header.contains("date")
                    || header.contains("time")
                    || header.contains("日期")
                    || header.contains("时间");
        };
    }

    private static String column(List<String> columns, Map<String, Integer> headerIndexes, String... names) {
        for (String name : names) {
            Integer index = headerIndexes.get(normalizeHeader(name));
            if (index != null && index < columns.size()) {
                return columns.get(index);
            }
        }

        return "";
    }

    private static LocalDateTime parseDateTime(String text, int lineNumber) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行缺少时间");
        }

        String trimmedText = stripByteOrderMark(text).trim();
        String normalizedText = trimmedText.replace('T', ' ');
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            for (String candidate : parseCandidates(normalizedText, trimmedText)) {
                try {
                    return LocalDateTime.parse(candidate, formatter);
                } catch (DateTimeParseException ignored) {
                    // Try the next supported format.
                }
            }
        }

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalizedText, formatter).atTime(LocalTime.of(23, 59));
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        throw new IllegalArgumentException("第 " + lineNumber + " 行时间格式无法识别：" + text);
    }

    private static boolean canParseDateTime(String text) {
        try {
            parseDateTime(text, 1);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static LocalDateTime parseOptionalDateTime(String text, int lineNumber) {
        if (text == null || text.isBlank()) {
            return null;
        }

        return parseDateTime(text, lineNumber);
    }

    private static LocalDateTime parseIcsDateTime(String text, int eventNumber) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("第 " + eventNumber + " 个日历事件缺少 DTSTART");
        }

        String normalizedText = text.trim();
        try {
            if (normalizedText.length() == 8) {
                return LocalDate.parse(normalizedText, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
            }
            if (normalizedText.endsWith("Z")) {
                return OffsetDateTime.parse(normalizedText, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX"))
                        .atZoneSameInstant(ZoneId.systemDefault())
                        .toLocalDateTime();
            }
            if (normalizedText.length() == 15) {
                return LocalDateTime.parse(normalizedText, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            }
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("第 " + eventNumber + " 个日历事件时间格式无法识别：" + text, exception);
        }

        throw new IllegalArgumentException("第 " + eventNumber + " 个日历事件时间格式无法识别：" + text);
    }

    private static LocalDateTime parseOptionalIcsDateTime(String text, int eventNumber) {
        if (text == null || text.isBlank()) {
            return null;
        }

        return parseIcsDateTime(text, eventNumber);
    }

    private static Duration parseReminderDuration(String text, CourseReminderType type) {
        if (text == null || text.isBlank()) {
            return CourseScheduleEntry.defaultRemindBefore(type);
        }

        String normalizedText = text.trim().toLowerCase(Locale.ROOT);
        if (normalizedText.endsWith("小时") || normalizedText.endsWith("hour") || normalizedText.endsWith("hours") || normalizedText.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(normalizedText.replace("小时", "")
                    .replace("hours", "")
                    .replace("hour", "")
                    .replace("h", "")
                    .trim()));
        }
        if (normalizedText.endsWith("天") || normalizedText.endsWith("day") || normalizedText.endsWith("days") || normalizedText.endsWith("d")) {
            return Duration.ofDays(Long.parseLong(normalizedText.replace("天", "")
                    .replace("days", "")
                    .replace("day", "")
                    .replace("d", "")
                    .trim()));
        }

        return Duration.ofMinutes(Long.parseLong(normalizedText
                .replace("分钟", "")
                .replace("minutes", "")
                .replace("minute", "")
                .replace("min", "")
                .replace("m", "")
                .trim()));
    }

    private static String combineDateAndTime(String date, String time) {
        if (date == null || date.isBlank() || time == null || time.isBlank()) {
            return "";
        }

        return date.trim() + " " + time.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }

        return second;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    private static String[] parseCandidates(String first, String second) {
        if (first.equals(second)) {
            return new String[]{first};
        }

        return new String[]{first, second};
    }

    private static String stripByteOrderMark(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }

        return text;
    }
}
