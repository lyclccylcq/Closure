package app.weather;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenMeteoWeatherClientTest {
    @Test
    void buildsForecastUriWithCurrentAndDailyWeather() {
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient(config());

        URI uri = client.forecastUri();

        String uriText = uri.toString();
        assertTrue(uriText.startsWith("https://api.open-meteo.test/v1/forecast?"));
        assertTrue(uriText.contains("latitude=-33.8688"));
        assertTrue(uriText.contains("longitude=151.2093"));
        assertTrue(uriText.contains("current=temperature_2m%2Capparent_temperature%2Cweather_code"));
        assertTrue(uriText.contains("daily=weather_code%2Ctemperature_2m_max"));
        assertTrue(uriText.contains("timezone=auto"));
        assertTrue(uriText.contains("forecast_days=1"));
    }

    @Test
    void parsesForecastResponse() {
        OpenMeteoWeatherClient client = new OpenMeteoWeatherClient(config());

        WeatherSnapshot snapshot = client.parseSnapshot("""
                {
                  "current": {
                    "time": "2026-08-01T09:15",
                    "temperature_2m": 18.4,
                    "apparent_temperature": 17.6,
                    "weather_code": 61,
                    "wind_speed_10m": 14.2,
                    "precipitation": 0.1
                  },
                  "daily": {
                    "time": ["2026-08-01"],
                    "weather_code": [63],
                    "temperature_2m_max": [22.1],
                    "temperature_2m_min": [12.3],
                    "precipitation_probability_max": [70],
                    "precipitation_sum": [4.8],
                    "wind_speed_10m_max": [26.4]
                  }
                }
                """);

        assertEquals("Sydney", snapshot.locationName());
        assertEquals(18.4, snapshot.currentTemperature());
        assertEquals(17.6, snapshot.apparentTemperature());
        assertEquals(61, snapshot.currentWeatherCode());
        assertEquals(22.1, snapshot.dailyMaxTemperature());
        assertEquals(12.3, snapshot.dailyMinTemperature());
        assertEquals(70, snapshot.precipitationProbabilityMax());
    }

    @Test
    void waitsForNativeLocationAndUsesOneResolvedPointForCoordinatesAndNameOnEveryLookup() throws Exception {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = forecastServer(queries);
        server.start();
        try {
            AtomicReference<CompletableFuture<WeatherLocation>> pending = new AtomicReference<>(new CompletableFuture<>());
            OpenMeteoWeatherClient client = new OpenMeteoWeatherClient(autoConfig(server), HttpClient.newHttpClient(), pending::get);
            CompletableFuture<WeatherSnapshot> first = client.currentWeather();
            assertFalse(first.isDone());
            assertTrue(queries.isEmpty());
            pending.get().complete(new WeatherLocation(-33.8688, 151.2093, "First City"));
            assertEquals("First City", first.get(3, TimeUnit.SECONDS).locationName());
            assertTrue(queries.getFirst().contains("latitude=-33.8688&longitude=151.2093"));
            pending.set(CompletableFuture.completedFuture(new WeatherLocation(-37.8136, 144.9631, "Second City")));
            assertEquals("Second City", client.currentWeather().get(3, TimeUnit.SECONDS).locationName());
            assertTrue(queries.get(1).contains("latitude=-37.8136&longitude=144.9631"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativeFailureUsesUnavailableGreetingWithoutAnyForecastRequestOrOldCity() throws Exception {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = forecastServer(queries);
        server.start();
        try {
            OpenMeteoWeatherClient client = new OpenMeteoWeatherClient(autoConfig(server), HttpClient.newHttpClient(),
                    () -> CompletableFuture.failedFuture(new WeatherException("未获系统定位授权")));
            String greeting = new WeatherGreetingService(client).startupGreeting().get(3, TimeUnit.SECONDS);
            assertTrue(greeting.contains("天气"));
            assertFalse(greeting.contains("Old City"));
            assertTrue(queries.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer forecastServer(List<String> queries) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forecast", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] response = """
                    {"current":{"temperature_2m":20,"apparent_temperature":19,"weather_code":0,"wind_speed_10m":5},
                     "daily":{"temperature_2m_max":[22],"temperature_2m_min":[12],"weather_code":[0],
                              "precipitation_probability_max":[0],"precipitation_sum":[0]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        return server;
    }

    private WeatherConfig autoConfig(HttpServer server) {
        return new WeatherConfig(-35.2809, 149.1300, "Old City",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/forecast"), Duration.ofSeconds(2),
                WeatherConfig.LocationMode.AUTO, null, null);
    }

    private WeatherConfig config() {
        return new WeatherConfig(
                -33.8688,
                151.2093,
                "Sydney",
                URI.create("https://api.open-meteo.test/v1/forecast"),
                Duration.ofSeconds(2)
        );
    }
}
