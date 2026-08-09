package app.reminder;

import java.io.IOException;
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
            formatter("d/M/yy H:mm"),
            formatter("d/M/yy H:mm:ss"),
            formatter("d/M/yy h:mm a"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            formatter("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            formatter("yyyy年M月d日"),
            formatter("d/M/yyyy"),
            formatter("d/M/yy")
    };
    private static final DateTimeFormatter[] TIME_FORMATTERS = {
            formatter("H:mm"),
            formatter("H:mm:ss"),
            formatter("h:mm a")
    };

    public List<CourseScheduleEntry> parse(Path scheduleFile) throws IOException {
        List<String> lines = CourseScheduleSourceReader.readLines(scheduleFile);
        return parseLines(lines);
    }

    public List<CourseScheduleEntry> parseLines(List<String> lines) {
        List<String> meaningfulLines = lines.stream()
                .map(CourseScheduleParser::stripByteOrderMark)
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && !isSpreadsheetSeparatorDirective(line))
                .toList();

        if (meaningfulLines.isEmpty()) {
            return List.of();
        }

        int calendarStartIndex = firstCalendarStartIndex(meaningfulLines);
        if (calendarStartIndex >= 0) {
            return parseIcs(meaningfulLines.subList(calendarStartIndex, meaningfulLines.size()));
        }

        int headerIndex = firstHeaderIndex(meaningfulLines);
        if (headerIndex >= 0) {
            return parseCsv(meaningfulLines, headerIndex);
        }

        return parseFlexibleText(meaningfulLines);
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

    private List<CourseScheduleEntry> parseCsv(List<String> lines, int headerIndex) {
        List<String> headers = splitDelimitedLine(lines.get(headerIndex));
        Map<String, Integer> headerIndexes = headerIndexes(headers);
        List<CourseScheduleEntry> entries = new ArrayList<>();

        for (int index = headerIndex + 1; index < lines.size(); index++) {
            List<String> columns = splitDelimitedLine(lines.get(index));
            entries.add(entryFromColumns(headerIndexes, columns, index + 1));
        }

        return entries;
    }

    private CourseScheduleEntry entryFromColumns(Map<String, Integer> headerIndexes, List<String> columns, int lineNumber) {
        String title = firstNonBlank(
                column(columns, headerIndexes, "title", "course", "courseTitle", "name", "subject", "summary",
                        "event", "eventTitle", "activity", "module", "unit", "lesson", "课程", "课程名称",
                        "标题", "事项", "活动", "名称", "科目", "主题"),
                fallbackTitle(columns, List.of())
        );
        String date = column(columns, headerIndexes, "date", "startDate", "日期", "开始日期");
        String startTime = column(columns, headerIndexes, "startTime", "开始时间", "time", "时间");
        String startDateTime = column(columns, headerIndexes, "start", "startsAt", "datetime", "startDate", "开始", "开始日期", "日期");
        String endDate = column(columns, headerIndexes, "endDate", "date", "结束日期", "日期");
        String endTime = column(columns, headerIndexes, "endTime", "结束时间");
        String endDateTime = column(columns, headerIndexes, "end", "endsAt", "endDate", "结束", "结束日期");
        LocalDateTime startsAt = parseDateTime(
                firstNonBlank(
                        combineDateAndTime(date, rangeStart(startTime)),
                        rangeStart(startDateTime)
                ),
                lineNumber
        );
        LocalDateTime endsAt = parseOptionalDateTime(
                firstNonBlank(
                        combineDateAndTime(
                                endDate,
                                firstNonBlank(endTime, rangeEnd(startTime))
                        ),
                        firstNonBlank(endDateTime, dateTimeRangeEnd(startDateTime))
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

    private List<CourseScheduleEntry> parseFlexibleText(List<String> lines) {
        List<CourseScheduleEntry> entries = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            List<String> columns = splitDelimitedLine(lines.get(index));
            if (columns.size() < 2) {
                continue;
            }

            CourseScheduleEntry simpleEntry = entryFromSimpleText(columns, index + 1);
            if (simpleEntry != null) {
                entries.add(simpleEntry);
                continue;
            }

            FlexibleRowTime rowTime = flexibleRowTime(columns, index + 1);
            if (rowTime == null) {
                continue;
            }

            String title = fallbackTitle(columns, rowTime.usedIndexes());
            CourseReminderType type = CourseReminderType.fromText(String.join(" ", columns));
            String location = fallbackLocation(columns, rowTime.usedIndexes(), title);
            String notes = fallbackNotes(columns, rowTime.usedIndexes(), title, location);
            entries.add(new CourseScheduleEntry(
                    title,
                    rowTime.startsAt(),
                    rowTime.endsAt(),
                    type,
                    CourseScheduleEntry.defaultRemindBefore(type),
                    location,
                    notes
            ));
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException("没有找到可识别的课程时间。请确认文件里有日期和时间，或导出为 CSV/ICS 后再试。");
        }

        return entries;
    }

    private CourseScheduleEntry entryFromSimpleText(List<String> columns, int lineNumber) {
        if (!canParseFullDateTime(columns.getFirst())) {
            return null;
        }

        LocalDateTime startsAt = parseDateTime(columns.getFirst(), lineNumber);
        String title = firstNonBlank(columns.get(1), "课程表事件");
        CourseReminderType type = columns.size() >= 3 && looksLikeReminderType(columns.get(2))
                ? CourseReminderType.fromText(columns.get(2))
                : CourseReminderType.fromText(title);
        int nextDetailIndex = columns.size() >= 3 && looksLikeReminderType(columns.get(2)) ? 3 : 2;
        Duration remindBefore = CourseScheduleEntry.defaultRemindBefore(type);
        if (nextDetailIndex < columns.size() && canParseReminderDuration(columns.get(nextDetailIndex))) {
            remindBefore = parseReminderDuration(columns.get(nextDetailIndex), type);
            nextDetailIndex++;
        }

        String location = nextDetailIndex < columns.size() ? columns.get(nextDetailIndex) : "";
        String notes = nextDetailIndex + 1 < columns.size()
                ? String.join(" ", columns.subList(nextDetailIndex + 1, columns.size()))
                : "";
        return new CourseScheduleEntry(title, startsAt, null, type, remindBefore, location, notes);
    }

    private FlexibleRowTime flexibleRowTime(List<String> columns, int lineNumber) {
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            String nextColumn = index + 1 < columns.size() ? columns.get(index + 1) : "";
            if (canParseDateOnly(column) && canParseTimeOnly(rangeStart(nextColumn))) {
                LocalDate date = parseDateOnly(column, lineNumber);
                LocalDateTime startsAt = date.atTime(parseTimeOnly(rangeStart(nextColumn), lineNumber));
                LocalDateTime endsAt = null;
                String rangeEnd = rangeEnd(nextColumn);
                if (!rangeEnd.isBlank() && canParseTimeOnly(rangeEnd)) {
                    endsAt = date.atTime(parseTimeOnly(rangeEnd, lineNumber));
                } else if (index + 2 < columns.size() && canParseTimeOnly(columns.get(index + 2))) {
                    endsAt = date.atTime(parseTimeOnly(columns.get(index + 2), lineNumber));
                }
                return new FlexibleRowTime(startsAt, endsAt, List.of(index, index + 1));
            }
        }

        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            if (canParseFullDateTime(column)) {
                LocalDateTime startsAt = parseDateTime(column, lineNumber);
                LocalDateTime endsAt = null;
                if (index + 1 < columns.size() && canParseFullDateTime(columns.get(index + 1))) {
                    endsAt = parseDateTime(columns.get(index + 1), lineNumber);
                }
                return new FlexibleRowTime(startsAt, endsAt, List.of(index));
            }
        }

        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            if (canParseDateOnly(column)) {
                return new FlexibleRowTime(parseDateOnly(column, lineNumber).atTime(LocalTime.of(23, 59)), null, List.of(index));
            }
        }

        return null;
    }

    private static boolean looksLikeHeader(List<String> columns) {
        if (columns.size() < 2 || canParseDateTime(columns.getFirst())) {
            return false;
        }

        long knownHeaderCount = columns.stream()
                .map(CourseScheduleParser::normalizeHeader)
                .filter(CourseScheduleParser::isKnownHeader)
                .count();
        return knownHeaderCount >= 2;
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
                 "名称", "科目", "主题", "date", "day", "when", "start", "from", "begin", "startsat",
                 "datetime", "startdatetime", "startdate", "startday", "starttime", "begintime",
                 "end", "to", "until", "finish", "endsat", "enddatetime", "enddate", "endtime",
                 "finishtime", "time", "日期", "上课日期", "开始", "开始日期", "开始日期时间", "开始时间",
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

    private static int firstCalendarStartIndex(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).equalsIgnoreCase("BEGIN:VCALENDAR")) {
                return index;
            }
        }

        return -1;
    }

    private static int firstHeaderIndex(List<String> lines) {
        int maxScanLines = Math.min(lines.size(), 20);
        for (int index = 0; index < maxScanLines; index++) {
            if (looksLikeHeader(splitDelimitedLine(lines.get(index)))) {
                return index;
            }
        }

        return -1;
    }

    private static LocalDateTime parseDateTime(String text, int lineNumber) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行缺少时间");
        }

        String trimmedText = stripByteOrderMark(text).trim();
        String normalizedText = normalizeDateTimeText(text);
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

    private static boolean canParseFullDateTime(String text) {
        if (text == null || text.isBlank() || canParseDateOnly(text)) {
            return false;
        }

        return canParseDateTime(text);
    }

    private static boolean canParseDateOnly(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalizedText = normalizeDateTimeText(text);
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate.parse(normalizedText, formatter);
                return true;
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        return false;
    }

    private static LocalDate parseDateOnly(String text, int lineNumber) {
        String normalizedText = normalizeDateTimeText(text);
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(normalizedText, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        throw new IllegalArgumentException("第 " + lineNumber + " 行日期格式无法识别：" + text);
    }

    private static boolean canParseTimeOnly(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalizedText = normalizeDateTimeText(text);
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                LocalTime.parse(normalizedText, formatter);
                return true;
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        return false;
    }

    private static LocalTime parseTimeOnly(String text, int lineNumber) {
        String normalizedText = normalizeDateTimeText(text);
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                return LocalTime.parse(normalizedText, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }

        throw new IllegalArgumentException("第 " + lineNumber + " 行时间格式无法识别：" + text);
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

    private static boolean canParseReminderDuration(String text) {
        try {
            parseReminderDuration(text, CourseReminderType.CLASS);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String combineDateAndTime(String date, String time) {
        if (date == null || date.isBlank() || time == null || time.isBlank()) {
            return "";
        }

        return date.trim() + " " + time.trim();
    }

    private static String rangeStart(String text) {
        List<String> parts = splitRange(text);
        if (parts.isEmpty()) {
            return "";
        }

        return parts.getFirst();
    }

    private static String rangeEnd(String text) {
        List<String> parts = splitRange(text);
        if (parts.size() < 2) {
            return "";
        }

        return parts.get(1);
    }

    private static String dateTimeRangeEnd(String text) {
        String start = rangeStart(text);
        String end = rangeEnd(text);
        if (end.isBlank()) {
            return "";
        }
        if (!canParseTimeOnly(end)) {
            return end;
        }

        String datePrefix = datePrefix(start);
        if (datePrefix.isBlank()) {
            return end;
        }

        return datePrefix + " " + end;
    }

    private static List<String> splitRange(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalizedText = normalizeDateTimeText(text)
                .replace("—", "-")
                .replace("–", "-")
                .replace("~", "-")
                .replace("至", "-")
                .replace("到", "-");
        int separatorIndex = rangeSeparatorIndex(normalizedText);
        if (separatorIndex < 0) {
            return List.of(normalizedText.trim());
        }

        String first = normalizedText.substring(0, separatorIndex).trim();
        String second = normalizedText.substring(separatorIndex + 1).trim();
        if (endsWithAmPm(second) && !endsWithAmPm(first)) {
            first = first + " " + amPmSuffix(second);
        }

        return List.of(first, second);
    }

    private static int rangeSeparatorIndex(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '-') {
                continue;
            }

            String before = text.substring(0, index).trim();
            if (looksLikeClockText(before)) {
                return index;
            }
        }

        return -1;
    }

    private static boolean looksLikeClockText(String text) {
        return text.contains(":") || endsWithAmPm(text);
    }

    private static String datePrefix(String dateTimeText) {
        if (dateTimeText == null || dateTimeText.isBlank()) {
            return "";
        }

        String[] parts = dateTimeText.trim().split("\\s+");
        if (parts.length < 2) {
            return "";
        }

        int timePartCount = endsWithAmPm(dateTimeText) && parts.length >= 3 ? 2 : 1;
        return String.join(" ", java.util.Arrays.copyOf(parts, parts.length - timePartCount));
    }

    private static String fallbackTitle(List<String> columns, List<Integer> excludedIndexes) {
        for (int index = 0; index < columns.size(); index++) {
            String value = columns.get(index);
            if (!excludedIndexes.contains(index) && isUsefulText(value)) {
                return value.trim();
            }
        }

        return "课程表事件";
    }

    private static String fallbackLocation(List<String> columns, List<Integer> excludedIndexes, String title) {
        for (int index = 0; index < columns.size(); index++) {
            String value = columns.get(index);
            if (!excludedIndexes.contains(index) && !value.equals(title) && isUsefulText(value)) {
                return value.trim();
            }
        }

        return "";
    }

    private static String fallbackNotes(List<String> columns, List<Integer> excludedIndexes, String title, String location) {
        List<String> notes = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            String value = columns.get(index);
            if (!excludedIndexes.contains(index)
                    && !value.equals(title)
                    && !value.equals(location)
                    && isUsefulText(value)) {
                notes.add(value.trim());
            }
        }

        return String.join(" ", notes);
    }

    private static boolean isUsefulText(String text) {
        return text != null
                && !text.isBlank()
                && !canParseDateTime(text)
                && !canParseTimeOnly(rangeStart(text));
    }

    private static boolean looksLikeReminderType(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalizedText = text.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedText) {
            case "class", "course", "lesson", "lecture", "tutorial", "lab", "seminar",
                 "deadline", "due", "ddl", "上课", "课程", "课", "作业", "截止", "截止日期" -> true;
            default -> normalizedText.contains("deadline")
                    || normalizedText.contains("due")
                    || normalizedText.contains("ddl")
                    || normalizedText.contains("lecture")
                    || normalizedText.contains("tutorial")
                    || normalizedText.contains("截止");
        };
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

    private static String normalizeDateTimeText(String text) {
        return stripByteOrderMark(text).trim()
                .replace('T', ' ')
                .replace('：', ':')
                .replace("上午", "AM")
                .replace("下午", "PM")
                .replaceAll("(?i)(\\d)(am|pm)\\b", "$1 $2")
                .replaceAll("(?i)\\bam\\b", "AM")
                .replaceAll("(?i)\\bpm\\b", "PM");
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

    private static boolean isSpreadsheetSeparatorDirective(String line) {
        return line.toLowerCase(Locale.ROOT).startsWith("sep=");
    }

    private static boolean endsWithAmPm(String text) {
        String normalizedText = text.trim().toUpperCase(Locale.ROOT);
        return normalizedText.endsWith(" AM") || normalizedText.endsWith(" PM");
    }

    private static String amPmSuffix(String text) {
        String normalizedText = text.trim().toUpperCase(Locale.ROOT);
        if (normalizedText.endsWith(" AM")) {
            return "AM";
        }
        if (normalizedText.endsWith(" PM")) {
            return "PM";
        }

        return "";
    }

    private record FlexibleRowTime(LocalDateTime startsAt, LocalDateTime endsAt, List<Integer> usedIndexes) {
    }
}
