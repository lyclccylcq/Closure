package app.weather;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

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
                fixedMorningClock()
        );

        String greeting = service.startupGreeting().join();

        assertTrue(greeting.contains("早上好，博士"));
        assertTrue(greeting.contains("Canberra 今天下雨"));
        assertTrue(greeting.contains("早晚会冷"));
        assertTrue(greeting.contains("外套"));
        assertTrue(greeting.contains("围巾"));
        assertTrue(greeting.contains("伞带上"));
        assertTrue(greeting.contains("烘干费"));
    }

    @Test
    void suggestsLightJacketForCoolDryDay() {
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
        WeatherGreetingService service = new WeatherGreetingService(
                () -> CompletableFuture.completedFuture(snapshot),
                fixedMorningClock()
        );

        String greeting = service.startupGreeting().join();

        assertTrue(greeting.contains("Canberra 今天局部多云"));
        assertTrue(greeting.contains("加件外套"));
        assertTrue(greeting.contains("别和冷风较劲"));
        assertTrue(!greeting.contains("降雨概率"));
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
                fixedMorningClock()
        );

        String greeting = service.startupGreeting().join();

        assertTrue(greeting.contains("Canberra 今天晴"));
        assertTrue(greeting.contains("水带上"));
        assertTrue(greeting.contains("防晒"));
        assertTrue(!greeting.contains("°C"));
    }

    @Test
    void fallsBackWhenWeatherFails() {
        WeatherGreetingService service = new WeatherGreetingService(
                () -> CompletableFuture.failedFuture(new WeatherException("offline")),
                fixedMorningClock()
        );

        String greeting = service.startupGreeting().join();

        assertTrue(greeting.contains("早上好，博士"));
        assertTrue(greeting.contains("天气模块暂时没连上"));
    }

    private Clock fixedMorningClock() {
        return Clock.fixed(
                Instant.parse("2026-08-01T22:30:00Z"),
                ZoneId.of("Australia/Sydney")
        );
    }
}
