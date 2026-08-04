package app.weather;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

public final class WeatherGreetingService {
    private final WeatherClient weatherClient;
    private final Clock clock;
    private final IntUnaryOperator variantSelector;

    public WeatherGreetingService(WeatherClient weatherClient) {
        this(weatherClient, Clock.systemDefaultZone());
    }

    WeatherGreetingService(WeatherClient weatherClient, Clock clock) {
        this(weatherClient, clock, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    WeatherGreetingService(WeatherClient weatherClient, Clock clock, IntUnaryOperator variantSelector) {
        this.weatherClient = Objects.requireNonNull(weatherClient, "weatherClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.variantSelector = Objects.requireNonNull(variantSelector, "variantSelector");
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

        return openingLine()
                + weatherLine(weather.locationName(), condition)
                + temperatureAdvice
                + rainAdvice;
    }

    private String fallbackGreeting() {
        return choose(
                timeGreeting() + "，博士。天气模块暂时没连上，先按窗外实况行动；终端等会儿再重试。",
                timeGreeting() + "，博士。天气接口还没回话，出门前看一眼窗外，我这边等会儿再查。",
                timeGreeting() + "。天气模块今天有点慢，先按实际天气准备，别被它坑了。"
        );
    }

    private String openingLine() {
        String greeting = timeGreeting();
        return choose(
                greeting + "，博士。",
                greeting + "。博士，我顺手看了下天气。",
                greeting + "，天气模块也上线了。"
        );
    }

    private String weatherLine(String locationName, String condition) {
        return choose(
                locationName + " 今天" + condition + "。",
                "我看了下 " + locationName + "，今天是" + condition + "。",
                locationName + " 今天的天气是" + condition + "，出门按这个准备。"
        );
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
            return choose(
                    "早晚会冷，外套和围巾别省，我可不想看你把自己当耐寒设备。",
                    "早晚温度压得低，外套和围巾带上，别把自己冻成终端配件。",
                    "今天早晚冷，外套围巾都安排上，别逞强。"
            );
        }
        if (coldestFeelsLike <= 12 || weather.dailyMaxTemperature() < 18) {
            return choose(
                    "气温偏凉，出门加件外套，别和冷风较劲。",
                    "外面不算暖，外套带一件，路上会舒服点。",
                    "今天有点凉，别只穿薄的出门，可露希尔不负责回收被冻住的博士。"
            );
        }
        if (weather.dailyMaxTemperature() >= 30) {
            return choose(
                    "白天会热，水带上，防晒也别偷懒。",
                    "白天气温会上去，水和防晒都带好，别把自己晒到掉线。",
                    "今天偏热，补水和防晒记一下，我可不想维修过热博士。"
            );
        }

        return choose(
                "温度还算听话，正常穿就行。",
                "气温还算省心，正常穿就好。",
                "今天温度没太折腾人，按平时穿就行。"
        );
    }

    private String rainAdvice(WeatherSnapshot weather) {
        if (weather.precipitationProbabilityMax() >= 35 || WeatherCode.isWetWeather(weather.dailyWeatherCode())) {
            return choose(
                    " 另外可能下雨，伞带上，淋湿了我可要收烘干费。",
                    " 还有下雨的可能，包里放把伞，省得路上被天气偷袭。",
                    " 雨具最好带一下，别等淋到了才想起工程部售后。"
            );
        }
        if (weather.precipitationProbabilityMax() >= 20) {
            return choose(
                    " 雨的概率不高，但小伞放包里也不亏。",
                    " 有一点下雨概率，小伞带不带你决定，我会偏向带。",
                    " 雨不一定来，不过包里留把小伞会安心点。"
            );
        }

        return "";
    }

    private String choose(String... variants) {
        if (variants.length == 0) {
            return "";
        }

        int selectedIndex = Math.floorMod(variantSelector.applyAsInt(variants.length), variants.length);
        return variants[selectedIndex];
    }
}
