package app.reminder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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
}
