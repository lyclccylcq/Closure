package app.weather;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/** Brief factual greetings without passing location or weather to a language model. */
public final class WeatherGreetingService {
    private final WeatherClient weatherClient;
    private final Clock clock;
    private final IntUnaryOperator variantSelector;

    public WeatherGreetingService(WeatherClient weatherClient) {
        this(weatherClient, Clock.systemDefaultZone());
    }

    public WeatherGreetingService(WeatherClient weatherClient, Clock clock) {
        this(weatherClient, clock, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    WeatherGreetingService(WeatherClient weatherClient, Clock clock, IntUnaryOperator variantSelector) {
        this.weatherClient = Objects.requireNonNull(weatherClient);
        this.clock = Objects.requireNonNull(clock);
        this.variantSelector = Objects.requireNonNull(variantSelector);
    }

    public static WeatherGreetingService fromEnvironment() {
        return new WeatherGreetingService(new OpenMeteoWeatherClient(WeatherConfig.fromEnvironment()));
    }

    public CompletableFuture<String> startupGreeting() {
        try {
            return weatherClient.currentWeather().thenApply(this::formatWeatherGreeting)
                    .exceptionally(error -> fallbackGreeting());
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(fallbackGreeting());
        }
    }

    String formatWeatherGreeting(WeatherSnapshot weather) {
        double low = weather.dailyMinTemperature(), high = weather.dailyMaxTemperature();
        if (!Double.isFinite(low) || !Double.isFinite(high) || low > high) return fallbackGreeting();
        String condition;
        if (isSnowy(weather)) condition = "今天有雪";
        else if (isRainLikely(weather)) condition = "今天可能有雨";
        else if (isFoggy(weather)) condition = "今天有雾";
        else condition = switch (weather.dailyWeatherCode()) {
            case 0 -> "今天晴天";
            case 1, 2 -> "今天晴间多云";
            case 3 -> "今天阴天";
            default -> "今天天气有些变化";
        };
        return openingLine() + condition + "，" + Math.round(low) + "到" + Math.round(high) + "度。"
                + adviceSentence(weather);
    }

    private String openingLine() {
        String greeting = timeGreeting();
        return choose(greeting + "，博士！", "博士，" + greeting + "！");
    }

    private String timeGreeting() {
        int hour = LocalDateTime.now(clock).getHour();
        if (hour >= 5 && hour < 11) return "早上好";
        if (hour >= 11 && hour < 13) return "中午好";
        if (hour >= 13 && hour < 18) return "下午好";
        return "晚上好";
    }

    private String fallbackGreeting() {
        return openingLine() + "天气还没查到，先不乱报。"
                + (isLate() ? "都这么晚啦，手头的事收个尾就休息吧。" : "忙归忙，水杯也别一直晾在旁边呀。");
    }

    private boolean isLate() {
        int hour = LocalDateTime.now(clock).getHour();
        return hour < 5 || hour >= 22;
    }

    private String adviceSentence(WeatherSnapshot weather) {
        if (isLate()) return choose("都这么晚啦，手头的事收个尾就休息吧。", "该给自己充充电啦，别熬太晚。");
        if (isSnowy(weather)) return choose("出门慢点，路滑可别逞强呀。", "把自己裹暖和点，路上也别赶。");
        if (isRainLikely(weather)) return choose("出门把伞捎上，我可不想看你淋成落汤鸡。", "伞记得带上，别一忙就忘啦。");
        if (isHot(weather)) return choose("水杯放手边，出门也别忘了防晒。", "别光顾着忙，记得喝水降降温。");
        if (isCold(weather)) return choose("出门带件外套，冻着了可不划算。", "手边备点热水，别让自己一直冷着呀。");
        if (weather.currentWindSpeed() >= 28) return choose("外套带上，手里的东西也拿稳啦。", "风大，出门把自己和东西都照顾好。");
        if (isFoggy(weather)) return "路上慢点，看清楚再走，不急这一会儿。";
        return choose("忙完起来走两步，别一直黏在椅子上呀。", "有空就歇会儿，水杯也别忘了。");
    }

    private static boolean isRainLikely(WeatherSnapshot weather) {
        return weather.precipitationProbabilityMax() >= 30
                || WeatherCode.isWetWeather(weather.currentWeatherCode())
                || WeatherCode.isWetWeather(weather.dailyWeatherCode());
    }

    private static boolean isSnowy(WeatherSnapshot weather) {
        return isSnowCode(weather.currentWeatherCode()) || isSnowCode(weather.dailyWeatherCode());
    }

    private static boolean isSnowCode(int code) {
        return switch (code) { case 71, 73, 75, 77, 85, 86 -> true; default -> false; };
    }

    private static boolean isHot(WeatherSnapshot weather) {
        return weather.apparentTemperature() >= 29 || weather.dailyMaxTemperature() >= 30;
    }

    private static boolean isCold(WeatherSnapshot weather) {
        return Math.min(weather.apparentTemperature(), weather.dailyMinTemperature()) <= 12
                || weather.dailyMaxTemperature() < 18;
    }

    private static boolean isFoggy(WeatherSnapshot weather) {
        return weather.currentWeatherCode() == 45 || weather.currentWeatherCode() == 48
                || weather.dailyWeatherCode() == 45 || weather.dailyWeatherCode() == 48;
    }

    private String choose(String... variants) {
        return variants[Math.floorMod(variantSelector.applyAsInt(variants.length), variants.length)];
    }
}
