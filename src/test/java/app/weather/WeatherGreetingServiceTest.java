package app.weather;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class WeatherGreetingServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test void greetingUsesLocalClockAcrossTimeBoundariesAndTimeZones() {
        Map<String,String> cases = Map.ofEntries(
                Map.entry("00:00", "晚上好"), Map.entry("04:59", "晚上好"), Map.entry("05:00", "早上好"),
                Map.entry("10:59", "早上好"), Map.entry("11:00", "中午好"), Map.entry("12:59", "中午好"),
                Map.entry("13:00", "下午好"), Map.entry("17:59", "下午好"), Map.entry("18:00", "晚上好"),
                Map.entry("23:59", "晚上好"));
        cases.forEach((time, expected) -> assertTrue(greeting(snapshot(0, 20, 25, 14), clock(time), 0).startsWith(expected), time));
        Instant instant = Instant.parse("2026-11-27T22:30:00Z");
        assertTrue(greeting(snapshot(0, 20, 25, 14), Clock.fixed(instant, ZONE), 0).startsWith("早上好"));
        assertTrue(greeting(snapshot(0, 20, 25, 14), Clock.fixed(instant, ZoneId.of("UTC")), 0).startsWith("晚上好"));
    }

    @Test void reportIsBriefFactualAndOmitsEveryLocationName() {
        for (String place : new String[]{"Canberra", "堪培拉", "当前位置", "Sydney"}) {
            for (int variant = 0; variant < 4; variant++) {
                WeatherSnapshot weather = new WeatherSnapshot(place, 18, 18, 2, 8, 24.4, 13.6, 2, 5, 0);
                String text = greeting(weather, clock("08:30"), variant);
                assertTrue(text.contains("14到24度")); assertTrue(text.contains("多云"));
                assertFalse(text.contains(place)); assertFalse(text.contains("堪培拉")); assertFalse(text.contains("最高气温"));
                assertTrue(text.length() >= 25 && text.length() <= 65, text);
                assertTrue(text.contains("博士"));
            }
        }
    }

    @Test void careMatchesRainSnowHeatColdFogAndLateNight() {
        String rainy = greeting(snapshot(63, 18, 24, 14), clock("12:00"), 0);
        assertTrue(rainy.contains("雨")); assertTrue(rainy.contains("伞"));
        String snowy = greeting(snapshot(71, -2, 4, -6), clock("08:00"), 0);
        assertTrue(snowy.contains("雪")); assertTrue(snowy.contains("路滑")); assertFalse(snowy.contains("伞"));
        String hot = greeting(snapshot(0, 30, 34, 24), clock("14:00"), 0);
        assertTrue(hot.contains("水杯")); assertTrue(hot.contains("防晒"));
        assertTrue(greeting(snapshot(3, 7, 10, 2), clock("08:00"), 0).contains("外套"));
        assertTrue(greeting(snapshot(45, 20, 24, 16), clock("08:00"), 0).contains("慢点"));
        String night = greeting(snapshot(0, 20, 25, 14), clock("23:00"), 0);
        assertTrue(night.contains("休息")); assertFalse(night.contains("晒太阳"));
    }

    @Test void unavailableOrInvalidWeatherKeepsTimeGreetingAndHonestFallback() {
        WeatherClient failed = () -> CompletableFuture.failedFuture(new WeatherException("offline"));
        WeatherClient throwing = () -> { throw new WeatherException("bad configuration"); };
        for (WeatherClient client : new WeatherClient[]{failed, throwing}) {
            String text = new WeatherGreetingService(client, clock("08:00"), bound -> 0).startupGreeting().join();
            assertTrue(text.startsWith("早上好")); assertTrue(text.contains("还没查到"));
            assertFalse(text.contains("度")); assertFalse(text.contains("offline"));
        }
        assertTrue(greeting(snapshot(0, 20, Double.NaN, 10), clock("08:00"), 0).contains("还没查到"));
        assertTrue(greeting(snapshot(0, 20, 10, 30), clock("08:00"), 0).contains("还没查到"));
    }

    @Test void windyDayCareIsSpecificAndVariantsRetainTheSameFacts() {
        WeatherSnapshot wind = new WeatherSnapshot("Canberra", 20, 20, 3, 32, 25, 14, 3, 5, 0);
        String first = greeting(wind, clock("08:00"), 0);
        String second = greeting(wind, clock("08:00"), 1);
        assertTrue(first.contains("拿稳")); assertTrue(second.contains("风大"));
        assertNotEquals(first, second);
        for (String text : new String[]{first, second}) {
            assertTrue(text.contains("今天阴天，14到25度")); assertFalse(text.contains("Canberra"));
        }
    }

    private static String greeting(WeatherSnapshot weather, Clock clock, int variant) {
        return new WeatherGreetingService(() -> CompletableFuture.completedFuture(weather), clock, bound -> variant).startupGreeting().join();
    }
    private static Clock clock(String time) { return Clock.fixed(java.time.LocalDateTime.parse("2026-11-28T" + time).atZone(ZONE).toInstant(), ZONE); }
    private static WeatherSnapshot snapshot(int code, double feels, double high, double low) {
        return new WeatherSnapshot("Canberra", feels, feels, code, 8, high, low, code, 5, 0);
    }
}
