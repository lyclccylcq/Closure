package app.reminder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public final class CourseScheduleRepository {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String HEADER = "title,start,end,type,remindBeforeMinutes,location,notes";

    private final Path schedulePath;
    private final CourseScheduleParser parser;

    public CourseScheduleRepository(Path schedulePath) {
        this.schedulePath = schedulePath;
        this.parser = new CourseScheduleParser();
    }

    public static CourseScheduleRepository userDefault() {
        Path appDirectory = Path.of(System.getProperty("user.home"), ".closure");
        return new CourseScheduleRepository(appDirectory.resolve("course-schedule.csv"));
    }

    public List<CourseScheduleEntry> load() throws IOException {
        if (!Files.isRegularFile(schedulePath)) {
            return List.of();
        }

        return parser.parse(schedulePath);
    }

    public List<CourseScheduleEntry> importAndSave(Path sourceFile) throws IOException {
        List<CourseScheduleEntry> entries = parser.parse(sourceFile);
        save(entries);
        return entries;
    }

    public void save(List<CourseScheduleEntry> entries) throws IOException {
        Files.createDirectories(schedulePath.getParent());
        List<String> lines = new java.util.ArrayList<>();
        lines.add(HEADER);
        entries.stream()
                .sorted(Comparator.comparing(CourseScheduleEntry::startsAt))
                .map(this::serialize)
                .forEach(lines::add);
        Files.write(schedulePath, lines, StandardCharsets.UTF_8);
    }

    public Path schedulePath() {
        return schedulePath;
    }

    private String serialize(CourseScheduleEntry entry) {
        return String.join(",",
                csv(entry.title()),
                csv(entry.startsAt().format(DATE_TIME_FORMATTER)),
                csv(entry.endsAt() == null ? "" : entry.endsAt().format(DATE_TIME_FORMATTER)),
                csv(entry.type().name().toLowerCase()),
                Long.toString(entry.remindBefore().toMinutes()),
                csv(entry.location()),
                csv(entry.notes())
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
