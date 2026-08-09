package app.reminder;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public interface CourseScheduleImportConverter extends AutoCloseable {
    CompletableFuture<List<String>> convertToScheduleLines(Path sourceFile, String parserErrorMessage);

    static CourseScheduleImportConverter disabled() {
        return new DisabledCourseScheduleImportConverter();
    }

    @Override
    default void close() {
    }

    final class DisabledCourseScheduleImportConverter implements CourseScheduleImportConverter {
        @Override
        public CompletableFuture<List<String>> convertToScheduleLines(Path sourceFile, String parserErrorMessage) {
            Objects.requireNonNull(sourceFile, "sourceFile");
            return CompletableFuture.failedFuture(new IllegalStateException("AI 课表转换未启用"));
        }
    }
}
