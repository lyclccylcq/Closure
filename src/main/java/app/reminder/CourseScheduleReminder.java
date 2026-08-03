package app.reminder;

import javax.swing.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class CourseScheduleReminder {
    private static final int CHECK_INTERVAL_MILLIS = 30_000;

    private final Timer timer;
    private final Clock clock;
    private final Consumer<String> reminderListener;
    private final Set<String> firedReminderKeys = new HashSet<>();
    private List<CourseScheduleEntry> entries;

    public CourseScheduleReminder(List<CourseScheduleEntry> entries, Consumer<String> reminderListener) {
        this(entries, Clock.systemDefaultZone(), reminderListener);
    }

    CourseScheduleReminder(List<CourseScheduleEntry> entries, Clock clock, Consumer<String> reminderListener) {
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

    public synchronized void setEntries(List<CourseScheduleEntry> newEntries) {
        entries = new ArrayList<>(Objects.requireNonNull(newEntries, "newEntries"));
        firedReminderKeys.retainAll(entries.stream().map(CourseScheduleEntry::stableKey).collect(java.util.stream.Collectors.toSet()));
        checkNow();
    }

    public synchronized List<CourseScheduleEntry> entries() {
        return List.copyOf(entries);
    }

    public void dispose() {
        timer.stop();
    }

    public synchronized void checkNow() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        for (CourseScheduleEntry entry : dueEntries(now)) {
            firedReminderKeys.add(entry.stableKey());
            reminderListener.accept(formatReminder(entry));
        }
    }

    synchronized List<CourseScheduleEntry> dueEntries(LocalDateTime now) {
        List<CourseScheduleEntry> dueEntries = new ArrayList<>();
        for (CourseScheduleEntry entry : entries) {
            if (firedReminderKeys.contains(entry.stableKey())) {
                continue;
            }

            LocalDateTime remindAt = entry.startsAt().minus(entry.remindBefore());
            if (!now.isBefore(remindAt) && now.isBefore(entry.startsAt().plus(Duration.ofMinutes(1)))) {
                dueEntries.add(entry);
            }
        }

        return dueEntries;
    }

    private String formatReminder(CourseScheduleEntry entry) {
        String leadText = entry.type() == CourseReminderType.DEADLINE ? "Deadline" : "上课";
        String placeText = entry.location().isBlank() ? "" : "，地点：" + entry.location();
        return "博士，" + leadText + "提醒：" + entry.title() + " 在 " + formatStartTime(entry) + placeText + "。";
    }

    private String formatStartTime(CourseScheduleEntry entry) {
        LocalDateTime startsAt = entry.startsAt();
        return startsAt.getMonthValue() + "月" + startsAt.getDayOfMonth() + "日 "
                + String.format("%02d:%02d", startsAt.getHour(), startsAt.getMinute());
    }
}
