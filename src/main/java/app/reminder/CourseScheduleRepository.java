package app.reminder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class CourseScheduleRepository {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String HEADER = "title,start,end,type,remindBeforeMinutes,location,notes";
    private static final long AI_CONVERSION_TIMEOUT_SECONDS = 120;

    private final Path schedulePath;
    private final CourseScheduleParser parser;
    private final CourseScheduleImportConverter importConverter;

    public CourseScheduleRepository(Path schedulePath) {
        this(schedulePath, CourseScheduleImportConverter.disabled());
    }

    public CourseScheduleRepository(Path schedulePath, CourseScheduleImportConverter importConverter) {
        this.schedulePath = schedulePath;
        this.parser = new CourseScheduleParser();
        this.importConverter = Objects.requireNonNull(importConverter, "importConverter");
    }

    public static CourseScheduleRepository userDefault() {
        return userDefault(CourseScheduleImportConverter.disabled());
    }

    public static CourseScheduleRepository userDefault(CourseScheduleImportConverter importConverter) {
        Path appDirectory = Path.of(System.getProperty("user.home"), ".closure");
        return new CourseScheduleRepository(appDirectory.resolve("course-schedule.csv"), importConverter);
    }

    public List<CourseScheduleEntry> load() throws IOException {
        if (!Files.isRegularFile(schedulePath)) {
            return List.of();
        }

        return parser.parse(schedulePath);
    }

    public List<CourseScheduleEntry> importAndSave(Path sourceFile) throws IOException {
        List<CourseScheduleEntry> entries = parseForImport(sourceFile);
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

    private List<CourseScheduleEntry> parseForImport(Path sourceFile) throws IOException {
        try {
            List<CourseScheduleEntry> entries = parser.parse(sourceFile);
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("课程表里没有可导入的课程或 Deadline。");
            }
            return entries;
        } catch (IOException | IllegalArgumentException exception) {
            return parseWithAiConverter(sourceFile, exception);
        }
    }

    private List<CourseScheduleEntry> parseWithAiConverter(Path sourceFile, Exception localParseException) throws IOException {
        try {
            List<String> convertedLines = importConverter
                    .convertToScheduleLines(sourceFile, localParseException.getMessage())
                    .orTimeout(AI_CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
            List<CourseScheduleEntry> entries = parser.parseLines(convertedLines);
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("AI 转换后的课程表没有可导入内容。");
            }

            return entries;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw importFailure(localParseException, cause);
        } catch (RuntimeException exception) {
            throw importFailure(localParseException, exception);
        }
    }

    private static RuntimeException importFailure(Exception localParseException, Throwable aiException) throws IOException {
        String message = "本地解析失败：" + localParseException.getMessage()
                + "；AI 转换失败：" + (aiException.getMessage() == null ? aiException.getClass().getSimpleName() : aiException.getMessage());
        if (aiException instanceof IOException ioException) {
            throw new IOException(message, ioException);
        }

        return new IllegalArgumentException(message, localParseException);
    }
}
