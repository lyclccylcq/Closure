package app.weather;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class WeatherGreetingService {
    private final WeatherClient weatherClient;
    private final Clock clock;

    public WeatherGreetingService(WeatherClient weatherClient) {
        this(weatherClient, Clock.systemDefaultZone());
    }

    WeatherGreetingService(WeatherClient weatherClient, Clock clock) {
        this.weatherClient = Objects.requireNonNull(weatherClient, "weatherClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static WeatherGreetingService fromEnvironment() {
        WeatherConfig config = WeatherConfig.fromEnvironment();
        return new WeatherGreetingService(new OpenMeteoWeatherClient(config));
    }

    public CompletableFuture<String> startupGreeting() {
        return weatherClient.currentWeather()
                .thenApply(this::formatWeatherGreeting)
                .exceptionally(error -> fallbackGreeting());
    }

    String formatWeatherGreeting(WeatherSnapshot weather) {
        String condition = WeatherCode.describe(weather.currentWeatherCode());
        String temperatureAdvice = temperatureAdvice(weather);
        String rainAdvice = rainAdvice(weather);

        return timeGreeting()
                + "，博士。"
                + weather.locationName()
                + " 今天" + condition + "。"
                + temperatureAdvice
                + rainAdvice;
    }

    private String fallbackGreeting() {
        return timeGreeting() + "，博士。天气模块暂时没连上，先按窗外实况行动；终端等会儿再重试。";
    }

    private String timeGreeting() {
        int hour = LocalDateTime.now(clock).getHour();
        if (hour < 5) {
            return "深夜还在线啊";
        }
        if (hour < 11) {
            return "早上好";
        }
        if (hour < 13) {
            return "中午好";
        }
        if (hour < 18) {
            return "下午好";
        }
        if (hour < 22) {
            return "晚上好";
        }

        return "夜间值班开始";
    }

    private static String rounded(double value) {
        return Long.toString(Math.round(value));
    }

    private String temperatureAdvice(WeatherSnapshot weather) {
        double coldestFeelsLike = Math.min(weather.apparentTemperature(), weather.dailyMinTemperature());
        if (coldestFeelsLike <= 6) {
            return "早晚会冷，外套和围巾别省，我可不想看你把自己当耐寒设备。";
        }
        if (coldestFeelsLike <= 12 || weather.dailyMaxTemperature() < 18) {
            return "气温偏凉，出门加件外套，别和冷风较劲。";
        }
        if (weather.dailyMaxTemperature() >= 30) {
            return "白天会热，水带上，防晒也别偷懒。";
        }

        return "温度还算听话，正常穿就行。";
    }

    private String rainAdvice(WeatherSnapshot weather) {
        if (weather.precipitationProbabilityMax() >= 35 || WeatherCode.isWetWeather(weather.dailyWeatherCode())) {
            return " 另外可能下雨，伞带上，淋湿了我可要收烘干费。";
        }
        if (weather.precipitationProbabilityMax() >= 20) {
            return " 雨的概率不高，但小伞放包里也不亏。";
        }

        return "";
    }
}
