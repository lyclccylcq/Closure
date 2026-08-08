package app.weather;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherGreetingServiceTest {
    @Test
    void formatsWeatherGreetingForStartup() {
        WeatherSnapshot snapshot = new WeatherSnapshot(
                "Canberra",
                18.4,
                5.6,
                61,
                14.2,
                22.1,
                12.3,
                63,
                70,
                4.8
        );
        WeatherGreetingService service = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                firstVariant()
        );

        String greeting = service.startupGreeting().join();

        assertNaturalStartupGreeting(greeting);
        assertTrue(greeting.contains("早上好，博士"));
        assertTrue(greeting.contains("堪培拉今天可能会下雨"));
        assertTrue(greeting.contains("带一把伞"));
        assertTrue(greeting.indexOf("下雨") < greeting.indexOf("伞"));
        assertDoesNotUseWeatherReportTone(greeting);
    }

    @Test
    void variesCareAdviceForColdDay() {
        WeatherSnapshot snapshot = new WeatherSnapshot(
                "Canberra",
                14.0,
                12.0,
                2,
                10.0,
                17.0,
                9.0,
                2,
                10,
                0
        );
        WeatherGreetingService firstService = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                firstVariant()
        );
        WeatherGreetingService secondService = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                secondVariant()
        );
        WeatherGreetingService thirdService = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                thirdVariant()
        );

        String firstGreeting = firstService.startupGreeting().join();
        String secondGreeting = secondService.startupGreeting().join();
        String thirdGreeting = thirdService.startupGreeting().join();

        assertNaturalStartupGreeting(firstGreeting);
        assertNaturalStartupGreeting(secondGreeting);
        assertNaturalStartupGreeting(thirdGreeting);
        assertTrue(firstGreeting.contains("堪培拉今天温度有点低"));
        assertTrue(firstGreeting.contains("热水"));
        assertTrue(firstGreeting.indexOf("冷") < firstGreeting.indexOf("热水"));
        assertTrue(secondGreeting.contains("温差"));
        assertTrue(thirdGreeting.contains("外套"));
        assertFalse(firstGreeting.contains("多穿"));
        assertNotEquals(firstGreeting, secondGreeting);
        assertNotEquals(secondGreeting, thirdGreeting);
        assertDoesNotUseWeatherReportTone(firstGreeting);
        assertDoesNotUseWeatherReportTone(secondGreeting);
        assertDoesNotUseWeatherReportTone(thirdGreeting);
    }

    @Test
    void suggestsWaterAndSunProtectionForHotDay() {
        WeatherSnapshot snapshot = new WeatherSnapshot(
                "Canberra",
                28.0,
                29.0,
                0,
                8.0,
                33.0,
                19.0,
                0,
                5,
                0
        );
        WeatherGreetingService service = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                firstVariant()
        );

        String greeting = service.startupGreeting().join();

        assertNaturalStartupGreeting(greeting);
        assertTrue(greeting.contains("堪培拉今天外面挺热"));
        assertTrue(greeting.contains("带水"));
        assertTrue(greeting.indexOf("热") < greeting.indexOf("水"));
        assertDoesNotUseWeatherReportTone(greeting);
    }

    @Test
    void suggestsWalkingForComfortableSunnyDay() {
        WeatherSnapshot snapshot = new WeatherSnapshot(
                "Canberra",
                20.0,
                19.0,
                0,
                8.0,
                24.0,
                13.0,
                0,
                5,
                0
        );
        WeatherGreetingService service = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                firstVariant()
        );

        String greeting = service.startupGreeting().join();

        assertNaturalStartupGreeting(greeting);
        assertTrue(greeting.contains("堪培拉今天是晴天"));
        assertTrue(greeting.contains("出去走走"));
        assertTrue(greeting.indexOf("晴天") < greeting.indexOf("走走"));
        assertDoesNotUseWeatherReportTone(greeting);
    }

    @Test
    void suggestsWindCareForWindyDay() {
        WeatherSnapshot snapshot = new WeatherSnapshot(
                "Canberra",
                18.4,
                17.8,
                3,
                30.0,
                22.1,
                14.3,
                3,
                5,
                0
        );
        WeatherGreetingService service = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                firstVariant()
        );

        String greeting = service.startupGreeting().join();

        assertNaturalStartupGreeting(greeting);
        assertTrue(greeting.contains("风有点大"));
        assertTrue(greeting.contains("东西也收好"));
        assertTrue(greeting.indexOf("风") < greeting.indexOf("收好"));
        assertDoesNotUseWeatherReportTone(greeting);
    }

    @Test
    void fallsBackWhenWeatherFails() {
        WeatherGreetingService service = new WeatherGreetingService(
                () -> CompletableFuture.failedFuture(new WeatherException("offline")),
                fixedMorningClock(),
                firstVariant()
        );

        String greeting = service.startupGreeting().join();

        assertTrue(greeting.contains("早上好，博士"));
        assertTrue(greeting.contains("天气"));
        assertDoesNotUseWeatherReportTone(greeting);
    }

    @Test
    void variesStartupGreetingTemplatesWithoutChangingMeaning() {
        WeatherSnapshot snapshot = new WeatherSnapshot(
                "Canberra",
                18.4,
                18.0,
                0,
                8.0,
                23.0,
                13.0,
                0,
                5,
                0
        );
        WeatherGreetingService firstService = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                firstVariant()
        );
        WeatherGreetingService secondService = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock(),
                secondVariant()
        );

        String firstGreeting = firstService.startupGreeting().join();
        String secondGreeting = secondService.startupGreeting().join();

        assertNotEquals(firstGreeting, secondGreeting);
        assertTrue(firstGreeting.contains("堪培拉"));
        assertTrue(secondGreeting.contains("堪培拉"));
        assertTrue(firstGreeting.contains("晴") || firstGreeting.contains("天气不错"));
        assertTrue(secondGreeting.contains("晴") || secondGreeting.contains("天气不错"));
        assertDoesNotUseWeatherReportTone(firstGreeting);
        assertDoesNotUseWeatherReportTone(secondGreeting);
    }

    private Clock fixedMorningClock() {
        return Clock.fixed(
                Instant.parse("2026-08-01T22:30:00Z"),
                ZoneId.of("Australia/Sydney")
        );
    }

    private IntUnaryOperator firstVariant() {
        return bound -> 0;
    }

    private IntUnaryOperator secondVariant() {
        return bound -> 1;
    }

    private IntUnaryOperator thirdVariant() {
        return bound -> 2;
    }

    private void assertNaturalStartupGreeting(String greeting) {
        int length = greeting.codePointCount(0, greeting.length());
        assertTrue(length >= 30, "greeting is too short: " + greeting);
        assertTrue(length <= 60, "greeting is too long: " + greeting);
    }

    private void assertDoesNotUseWeatherReportTone(String greeting) {
        assertFalse(greeting.contains("当前天气状况"));
        assertFalse(greeting.contains("最高气温"));
        assertFalse(greeting.contains("降水概率"));
        assertFalse(greeting.contains("摄氏度"));
        assertFalse(greeting.contains("°C"));
        assertFalse(greeting.contains("请根据天气情况"));
        assertFalse(greeting.contains("出门按这个准备"));
        assertFalse(greeting.contains("烘干费"));
        assertFalse(greeting.contains("终端"));
        assertFalse(greeting.contains("维修"));
    }
}
