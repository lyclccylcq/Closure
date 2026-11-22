package app.weather;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class WeatherConfigTest {
    @Test
    void automaticModeIgnoresLegacyCoordinatesAndCity() {
        Properties properties = new Properties();
        properties.setProperty("weather.latitude", "old invalid coordinate");
        properties.setProperty("weather.longitude", "149.1300");
        properties.setProperty("weather.locationName", "Old City");
        WeatherConfig config = WeatherConfig.from(Map.of(), properties);
        assertEquals(WeatherConfig.LocationMode.AUTO, config.locationMode());
        assertEquals("当前位置", config.locationName());
        assertTrue(Double.isNaN(config.latitude()));
        assertTrue(Double.isNaN(config.longitude()));
        assertThrows(WeatherException.class, () -> new OpenMeteoWeatherClient(config).forecastUri());
    }

    @Test
    void manualModeRequiresValidCoordinatesAndPreservesEnvironmentPriority() {
        Properties properties = new Properties();
        properties.setProperty("weather.locationMode", "fixed");
        assertThrows(WeatherException.class, () -> WeatherConfig.from(Map.of(), properties));
        properties.setProperty("weather.latitude", "-35.2809");
        properties.setProperty("weather.longitude", "149.1300");
        properties.setProperty("weather.locationName", "Chosen City");
        WeatherConfig config = WeatherConfig.from(Map.of("CLOSURE_WEATHER_LATITUDE", "-33.8688"), properties);
        assertEquals(WeatherConfig.LocationMode.FIXED, config.locationMode());
        assertEquals(-33.8688, config.latitude());
        assertEquals(149.1300, config.longitude());
        assertEquals("Chosen City", config.locationName());
        properties.setProperty("weather.latitude", "NaN");
        assertThrows(WeatherException.class, () -> WeatherConfig.from(Map.of(), properties));
        properties.setProperty("weather.latitude", "91");
        assertThrows(WeatherException.class, () -> WeatherConfig.from(Map.of(), properties));
    }

    @Test
    void explicitAutomaticModeAndHelperPathOverrideManualProperties() {
        Properties properties = new Properties();
        properties.setProperty("weather.locationMode", "fixed");
        WeatherConfig config = WeatherConfig.from(Map.of(
                "CLOSURE_WEATHER_LOCATION_MODE", " AUTO ",
                "CLOSURE_WEATHER_LOCATION_HELPER", "/some/runtime/ClosureLocation.app"), properties);
        assertEquals(WeatherConfig.LocationMode.AUTO, config.locationMode());
        assertEquals("/some/runtime/ClosureLocation.app", config.locationHelper().toString());
        assertThrows(WeatherException.class, () -> WeatherConfig.from(
                Map.of("CLOSURE_WEATHER_LOCATION_MODE", "unknown"), new Properties()));
    }
}
