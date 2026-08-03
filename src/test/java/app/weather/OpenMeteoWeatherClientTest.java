package app.weather;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
