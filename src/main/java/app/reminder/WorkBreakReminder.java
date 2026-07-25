package app.reminder;

import javax.swing.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * WorkBreakReminder 负责桌宠的后台工作提醒计时。
 * 这个类只处理“什么时候提醒”和“提醒文案是什么”，不直接操作任何 UI。
 */
public final class WorkBreakReminder {
    public static final Duration MIN_WORK_INTERVAL = Duration.ofMinutes(5);
    public static final Duration MAX_WORK_INTERVAL = Duration.ofHours(2);

    private static final String[] REMINDER_TEMPLATES = {
            "博士，已经连续工作 %s 了。喝点水，站起来活动一下。",
            "工程部提醒：连续运转 %s，先离开椅子三分钟。维修费之后再算。",
            "终端记录显示你坐太久了，%s 到点。起来伸展一下，别让我派无人机来催。",
            "%s 到了，暂停一下。水杯、肩颈、眼睛，一个都别漏。",
            "博士，这轮 %s 结束了。休息不是偷懒，是维护设备。你也是设备，先喝水。"
    };

    private final Timer timer;
    private final Consumer<String> reminderListener;
    private Duration workInterval;

    public WorkBreakReminder(Duration workInterval, Consumer<String> reminderListener) {
        this.workInterval = requireSupportedDuration(workInterval);
        this.reminderListener = Objects.requireNonNull(reminderListener, "reminderListener");
        timer = new Timer(toTimerDelayMillis(workInterval), event -> triggerReminder());
        timer.setRepeats(true);
    }

    /**
     * 开始提醒计时。
     * 如果计时器已经在运行，就不会重复启动。
     */
    public void start() {
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    /**
     * 暂停提醒计时。
     */
    public void pause() {
        timer.stop();
    }

    /**
     * 从当前时间重新开始计算下一次提醒。
     */
    public void restart() {
        timer.restart();
    }

    /**
     * 修改提醒间隔。
     * 如果计时器正在运行，会从当前时间重新开始计算下一次提醒。
     */
    public void setWorkInterval(Duration newWorkInterval) {
        boolean wasRunning = timer.isRunning();
        workInterval = requireSupportedDuration(newWorkInterval);
        int delayMillis = toTimerDelayMillis(workInterval);
        timer.setDelay(delayMillis);
        timer.setInitialDelay(delayMillis);

        if (wasRunning) {
            timer.restart();
        }
    }

    public boolean isRunning() {
        return timer.isRunning();
    }

    public Duration workInterval() {
        return workInterval;
    }

    public void dispose() {
        timer.stop();
    }

    private void triggerReminder() {
        reminderListener.accept(randomReminderLine());
    }

    private String randomReminderLine() {
        int lineIndex = ThreadLocalRandom.current().nextInt(REMINDER_TEMPLATES.length);
        return String.format(REMINDER_TEMPLATES[lineIndex], reminderIntervalText());
    }

    private static Duration requirePositiveDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("workInterval must be positive");
        }

        return duration;
    }

    private static Duration requireSupportedDuration(Duration duration) {
        requirePositiveDuration(duration);
        if (duration.compareTo(MIN_WORK_INTERVAL) < 0 || duration.compareTo(MAX_WORK_INTERVAL) > 0) {
            throw new IllegalArgumentException("workInterval must be between 5 minutes and 2 hours");
        }

        return duration;
    }

    private static int toTimerDelayMillis(Duration duration) {
        long millis = duration.toMillis();
        if (millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("workInterval is too long for Swing Timer");
        }

        return (int) millis;
    }

    private String reminderIntervalText() {
        long hours = workInterval.toHours();
        if (hours > 0 && workInterval.minusHours(hours).isZero()) {
            return hours + " 小时";
        }

        return workInterval.toMinutes() + " 分钟";
    }
}
