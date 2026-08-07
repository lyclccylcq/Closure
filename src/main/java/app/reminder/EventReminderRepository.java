package app.reminder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EventReminderRepository {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String HEADER = "event,remindAt,reminderText";

    private final Path remindersPath;

    public EventReminderRepository(Path remindersPath) {
        this.remindersPath = remindersPath;
    }

    public static EventReminderRepository userDefault() {
        Path appDirectory = Path.of(System.getProperty("user.home"), ".closure");
        return new EventReminderRepository(appDirectory.resolve("event-reminders.csv"));
    }

    public List<EventReminderEntry> load() throws IOException {
        if (!Files.isRegularFile(remindersPath)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(remindersPath, StandardCharsets.UTF_8)
                .stream()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
        if (lines.isEmpty()) {
            return List.of();
        }

        int firstEntryIndex = looksLikeHeader(lines.getFirst()) ? 1 : 0;
        List<EventReminderEntry> entries = new ArrayList<>();
        for (int index = firstEntryIndex; index < lines.size(); index++) {
            entries.add(parseLine(lines.get(index), index + 1));
        }

        return sortedEntries(entries);
    }

    public List<EventReminderEntry> loadForDate(LocalDate date) throws IOException {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }

        return load().stream()
                .filter(entry -> entry.remindAt().toLocalDate().equals(date))
                .toList();
    }

    public List<EventReminderEntry> add(EventReminderEntry entry) throws IOException {
        List<EventReminderEntry> entries = new ArrayList<>(loadForDate(entry.remindAt().toLocalDate()));
        entries.add(entry);
        save(entries);
        return sortedEntries(entries);
    }

    public List<EventReminderEntry> remove(EventReminderEntry entry) throws IOException {
        String removedKey = entry.stableKey();
        List<EventReminderEntry> remainingEntries = loadForDate(entry.remindAt().toLocalDate()).stream()
                .filter(savedEntry -> !savedEntry.stableKey().equals(removedKey))
                .toList();
        save(remainingEntries);
        return remainingEntries;
    }

    public void save(List<EventReminderEntry> entries) throws IOException {
        Path parentDirectory = remindersPath.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        sortedEntries(entries).stream()
                .map(this::serialize)
                .forEach(lines::add);
        Files.write(remindersPath, lines, StandardCharsets.UTF_8);
    }

    public Path remindersPath() {
        return remindersPath;
    }

    private EventReminderEntry parseLine(String line, int lineNumber) {
        List<String> columns = CourseScheduleParser.splitDelimitedLine(line);
        if (columns.size() < 3) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行至少需要事件、提醒时间和提醒文案");
        }

        return new EventReminderEntry(
                columns.get(0),
                EventReminderDateTimeParser.parse(columns.get(1)),
                columns.get(2)
        );
    }

    private String serialize(EventReminderEntry entry) {
        return String.join(",",
                csv(entry.eventText()),
                csv(entry.remindAt().format(DATE_TIME_FORMATTER)),
                csv(entry.reminderText())
        );
    }

    private static List<EventReminderEntry> sortedEntries(List<EventReminderEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparing(EventReminderEntry::remindAt))
                .toList();
    }

    private static boolean looksLikeHeader(String line) {
        List<String> columns = CourseScheduleParser.splitDelimitedLine(line);
        return columns.stream().map(String::toLowerCase).anyMatch(column ->
                column.equals("event") || column.equals("remindat") || column.equals("remindertext")
        );
    }

    private static String csv(String text) {
        String value = text == null ? "" : text;
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }
}
