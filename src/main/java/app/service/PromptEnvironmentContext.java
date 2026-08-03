package app.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class PromptEnvironmentContext {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final Clock clock;

    public PromptEnvironmentContext() {
        this(Clock.systemDefaultZone());
    }

    PromptEnvironmentContext(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String currentContextPrompt() {
        LocalDateTime now = LocalDateTime.now(clock);
        ZoneId zone = clock.getZone();

        return """

                当前环境信息：
                - 当前日期：%s（%s）
                - 当前时间：%s
                - 当前时区：%s
                - 当前季节：%s
                - 当前时段：%s
                使用这些信息理解博士说的“今天、明天、今晚、这周、现在、早上、下午、季节”等相对表达。除非博士主动问时间或日期，不要生硬复述环境信息。
                """.formatted(
                now.format(DATE_FORMATTER),
                chineseDayOfWeek(now),
                now.format(TIME_FORMATTER),
                zone,
                southernHemisphereSeason(now.getMonth()),
                dayPeriod(now.getHour())
        );
    }

    private static String chineseDayOfWeek(LocalDateTime dateTime) {
        return switch (dateTime.getDayOfWeek()) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private static String southernHemisphereSeason(Month month) {
        return switch (month) {
            case DECEMBER, JANUARY, FEBRUARY -> "夏季";
            case MARCH, APRIL, MAY -> "秋季";
            case JUNE, JULY, AUGUST -> "冬季";
            case SEPTEMBER, OCTOBER, NOVEMBER -> "春季";
        };
    }

    private static String dayPeriod(int hour) {
        if (hour < 5) {
            return "凌晨";
        }
        if (hour < 11) {
            return "上午";
        }
        if (hour < 13) {
            return "中午";
        }
        if (hour < 18) {
            return "下午";
        }
        if (hour < 22) {
            return "晚上";
        }

        return "深夜";
    }
}
