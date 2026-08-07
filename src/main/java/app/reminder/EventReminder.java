package app.reminder;

import javax.swing.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class EventReminder {
    private static final int CHECK_INTERVAL_MILLIS = 30_000;
    private static final Duration REMINDER_WINDOW = Duration.ofMinutes(1);

    private final Timer timer;
    private final Clock clock;
    private final Consumer<EventReminderEntry> reminderListener;
    private final Set<String> firedReminderKeys = new HashSet<>();
    private List<EventReminderEntry> entries;

    public EventReminder(List<EventReminderEntry> entries, Consumer<EventReminderEntry> reminderListener) {
        this(entries, Clock.systemDefaultZone(), reminderListener);
    }

    EventReminder(List<EventReminderEntry> entries, Clock clock, Consumer<EventReminderEntry> reminderListener) {
        this.entries = new ArrayList<>(Objects.requireNonNull(entries, "entries"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reminderListener = Objects.requireNonNull(reminderListener, "reminderListener");
        timer = new Timer(CHECK_INTERVAL_MILLIS, event -> checkNow());
        timer.setRepeats(true);
    }

    public void start() {
        if (!timer.isRunning()) {
            timer.start();
        }
        checkNow();
    }

    public synchronized void setEntries(List<EventReminderEntry> newEntries) {
        entries = new ArrayList<>(Objects.requireNonNull(newEntries, "newEntries"));
        firedReminderKeys.retainAll(entries.stream().map(EventReminderEntry::stableKey).collect(Collectors.toSet()));
        checkNow();
    }

    public synchronized List<EventReminderEntry> entries() {
        return List.copyOf(entries);
    }

    public void dispose() {
        timer.stop();
    }

    public synchronized void checkNow() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        for (EventReminderEntry entry : dueEntries(now)) {
            firedReminderKeys.add(entry.stableKey());
            reminderListener.accept(entry);
        }
    }

    synchronized List<EventReminderEntry> dueEntries(LocalDateTime now) {
        List<EventReminderEntry> dueEntries = new ArrayList<>();
        for (EventReminderEntry entry : entries) {
            if (firedReminderKeys.contains(entry.stableKey())) {
                continue;
            }

            if (!now.isBefore(entry.remindAt()) && now.isBefore(entry.remindAt().plus(REMINDER_WINDOW))) {
                dueEntries.add(entry);
            }
        }

        return dueEntries;
    }
}
