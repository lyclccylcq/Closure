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
        return openingLine()
                + weatherSentence(weather)
                + adviceSentence(weather);
    }

    private String fallbackGreeting() {
        return choose(
                timeGreeting() + "，博士。今天的天气我这边还没拿到，出门前看一眼窗外。",
                timeGreeting() + "。天气信息暂时没连上，出门前记得自己确认一下。",
                timeGreeting() + "，博士。天气模块还没回话，先按窗外情况准备。"
        );
    }

    private String openingLine() {
        String greeting = timeGreeting();
        return choose(
                greeting + "，博士。",
                "博士，" + greeting + "。",
                greeting + "，博士。"
        );
    }

    private String weatherSentence(WeatherSnapshot weather) {
        String place = locationPrefix(weather.locationName());
        if (isSnowy(weather)) {
            return choose(
                    place + "今天可能会下雪。",
                    place + "今天外面可能有雪。"
            );
        }
        if (isRainLikely(weather)) {
            return choose(
                    place + "今天可能会下雨。",
                    place + "今天有下雨的可能。"
            );
        }
        if (isHot(weather)) {
            return choose(
                    place + "今天外面挺热的。",
                    place + "今天温度有点高。"
            );
        }
        if (isCold(weather)) {
            return choose(
                    place + "今天温度有点低，早晚会比较冷。",
                    place + "今天有点冷，早晚温差也要注意。"
            );
        }
        if (isWindy(weather)) {
            return choose(
                    place + "今天风有点大。",
                    place + "今天外面风比较明显。"
            );
        }
        if (isSunny(weather)) {
            return choose(
                    place + "今天是晴天，温度也挺舒服的。",
                    place + "今天天气不错，温度也合适。"
            );
        }
        if (isCloudy(weather)) {
            return choose(
                    place + "今天有点阴，外面比看起来凉一些。",
                    place + "今天云比较多，体感会稍微凉一点。"
            );
        }
        if (isFoggy(weather)) {
            return choose(
                    place + "今天外面有雾。",
                    place + "今天能见度可能不太好。"
            );
        }

        return choose(
                place + "今天天气还算平稳。",
                place + "今天没有特别明显的坏天气。"
        );
    }

    private String timeGreeting() {
        int hour = LocalDateTime.now(clock).getHour();
        if (hour < 5) {
            return "晚上好";
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

        return "晚上好";
    }

    private String adviceSentence(WeatherSnapshot weather) {
        if (isRainLikely(weather)) {
            return choose(
                    "出门的时候记得带一把伞，路上会安心一点。",
                    "出门的时候带把伞比较稳。",
                    "路上留点余量，别被雨堵住节奏。"
            );
        }
        if (isSnowy(weather)) {
            return choose(
                    "路上可能会滑，走慢一点比较稳。",
                    "鞋底和路面都注意一下，别赶得太急。",
                    "先把手边的热饮安排上，别硬扛冷。"
            );
        }
        if (isHot(weather)) {
            return choose(
                    "出去记得带水，也别在太阳下待太久。",
                    "出门注意防晒，水也带上。",
                    "中午那段少在外面晒，活动放轻一点。"
            );
        }
        if (isCold(weather)) {
            return choose(
                    "手边放点热水，别让自己一直冷着。",
                    "早晚温差会明显，行程别排得太赶。",
                    "如果要出门，带件外套会舒服些。",
                    "窗户别开太久，冷风进来会很快。"
            );
        }
        if (isWindy(weather)) {
            return choose(
                    "出门加件外套，东西也收好。",
                    "出门注意风，轻的东西别随手放。",
                    "头发和文件都看好，风会有点烦人。"
            );
        }
        if (isFoggy(weather)) {
            return choose(
                    "路上注意一点，别赶得太急。",
                    "出门看路况，慢一点更安心。"
            );
        }
        if (isSunny(weather)) {
            return choose(
                    "有空可以出去走走。",
                    "有时间就晒晒太阳，活动一下。",
                    "午后找个亮一点的地方待会儿也不错。"
            );
        }

        return choose(
                "有空可以出去透透气。",
                "正常安排就行，别一直闷在屋里。",
                "今天按自己的节奏来，不用太紧绷。"
        );
    }

    private static String locationPrefix(String locationName) {
        if (locationName == null || locationName.isBlank()) {
            return "";
        }

        return displayLocationName(locationName);
    }

    private static String displayLocationName(String locationName) {
        if (locationName == null || locationName.isBlank()) {
            return "";
        }

        String normalizedName = locationName.trim();
        return switch (normalizedName.toLowerCase(java.util.Locale.ROOT)) {
            case "canberra" -> "堪培拉";
            case "sydney" -> "悉尼";
            case "melbourne" -> "墨尔本";
            case "brisbane" -> "布里斯班";
            case "perth" -> "珀斯";
            case "adelaide" -> "阿德莱德";
            default -> normalizedName;
        };
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
        return switch (code) {
            case 71, 73, 75, 77, 85, 86 -> true;
            default -> false;
        };
    }

    private static boolean isHot(WeatherSnapshot weather) {
        return weather.apparentTemperature() >= 29 || weather.dailyMaxTemperature() >= 30;
    }

    private static boolean isCold(WeatherSnapshot weather) {
        double coldestFeelsLike = Math.min(weather.apparentTemperature(), weather.dailyMinTemperature());
        return coldestFeelsLike <= 12 || weather.dailyMaxTemperature() < 18;
    }

    private static boolean isWindy(WeatherSnapshot weather) {
        return weather.currentWindSpeed() >= 28;
    }

    private static boolean isSunny(WeatherSnapshot weather) {
        return weather.currentWeatherCode() == 0 || weather.currentWeatherCode() == 1;
    }

    private static boolean isCloudy(WeatherSnapshot weather) {
        return weather.currentWeatherCode() == 2 || weather.currentWeatherCode() == 3;
    }

    private static boolean isFoggy(WeatherSnapshot weather) {
        return weather.currentWeatherCode() == 45 || weather.currentWeatherCode() == 48;
    }

    private String choose(String... variants) {
        if (variants.length == 0) {
            return "";
        }

        int selectedIndex = Math.floorMod(variantSelector.applyAsInt(variants.length), variants.length);
        return variants[selectedIndex];
    }
}
