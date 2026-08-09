package app.reminder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseScheduleRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void importsAndSavesNormalizedSchedule() throws Exception {
        Path source = tempDir.resolve("schedule.csv");
        Files.write(source, List.of(
                "title,start,type,remindBeforeMinutes,location",
                "编译原理,2026-08-06 10:00,class,20,C301"
        ));

        Path saved = tempDir.resolve("saved").resolve("course-schedule.csv");
        CourseScheduleRepository repository = new CourseScheduleRepository(saved);

        List<CourseScheduleEntry> importedEntries = repository.importAndSave(source);
        List<CourseScheduleEntry> loadedEntries = repository.load();

        assertTrue(Files.isRegularFile(saved));
        assertEquals(importedEntries, loadedEntries);
        assertEquals("编译原理", loadedEntries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 6, 10, 0), loadedEntries.getFirst().startsAt());
        assertEquals(Duration.ofMinutes(20), loadedEntries.getFirst().remindBefore());
    }

    @Test
    void fallsBackToAiConverterWhenLocalImportFails() throws Exception {
        Path source = tempDir.resolve("messy-schedule.txt");
        Files.writeString(source, "算法课：2026年8月7日 11:50-13:00，C101");
        Path saved = tempDir.resolve("saved-ai").resolve("course-schedule.csv");
        CourseScheduleRepository repository = new CourseScheduleRepository(
                saved,
                (sourceFile, parserErrorMessage) -> CompletableFuture.completedFuture(List.of(
                        "title,start,end,type,remindBeforeMinutes,location,notes",
                        "算法课,2026-08-07 11:50,2026-08-07 13:00,class,15,C101,"
                ))
        );

        List<CourseScheduleEntry> importedEntries = repository.importAndSave(source);
        List<CourseScheduleEntry> loadedEntries = repository.load();

        assertEquals(importedEntries, loadedEntries);
        assertEquals("算法课", loadedEntries.getFirst().title());
        assertEquals(LocalDateTime.of(2026, 8, 7, 11, 50), loadedEntries.getFirst().startsAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 13, 0), loadedEntries.getFirst().endsAt());
        assertEquals("C101", loadedEntries.getFirst().location());
    }
}
