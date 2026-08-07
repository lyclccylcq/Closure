package app.reminder;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

public interface EventReminderTextGenerator extends AutoCloseable {
    CompletableFuture<String> generateReminderText(String eventText, LocalDateTime remindAt);

    @Override
    default void close() {
    }
}
