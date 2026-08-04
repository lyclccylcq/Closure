package app.weather;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record WeatherConfig(
        double latitude,
        double longitude,
        String locationName,
        URI forecastEndpoint,
        Duration requestTimeout
) {
    public static final String ENV_LATITUDE = "CLOSURE_WEATHER_LATITUDE";
    public static final String ENV_LONGITUDE = "CLOSURE_WEATHER_LONGITUDE";
    public static final String ENV_LOCATION_NAME = "CLOSURE_WEATHER_LOCATION_NAME";
    public static final String ENV_FORECAST_ENDPOINT = "CLOSURE_WEATHER_FORECAST_ENDPOINT";

    public static final String PROPERTY_LATITUDE = "weather.latitude";
    public static final String PROPERTY_LONGITUDE = "weather.longitude";
    public static final String PROPERTY_LOCATION_NAME = "weather.locationName";
    public static final String PROPERTY_FORECAST_ENDPOINT = "weather.forecastEndpoint";

    private static final Path LOCAL_CONFIG_PATH = Path.of("config", "local-weather.properties");
    private static final double DEFAULT_LATITUDE = -35.2809;
    private static final double DEFAULT_LONGITUDE = 149.1300;
    private static final String DEFAULT_LOCATION_NAME = "堪培拉";
    private static final URI DEFAULT_FORECAST_ENDPOINT = URI.create("https://api.open-meteo.com/v1/forecast");
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(8);

    public WeatherConfig {
        if (locationName == null || locationName.isBlank()) {
            locationName = DEFAULT_LOCATION_NAME;
        }
        if (forecastEndpoint == null) {
            forecastEndpoint = DEFAULT_FORECAST_ENDPOINT;
        }
        if (requestTimeout == null) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
    }

    public static WeatherConfig fromEnvironment() {
        Properties properties = loadLocalProperties();
        return new WeatherConfig(
                configDouble(ENV_LATITUDE, PROPERTY_LATITUDE, properties, DEFAULT_LATITUDE),
                configDouble(ENV_LONGITUDE, PROPERTY_LONGITUDE, properties, DEFAULT_LONGITUDE),
                configValueOrDefault(ENV_LOCATION_NAME, PROPERTY_LOCATION_NAME, properties, DEFAULT_LOCATION_NAME),
                URI.create(configValueOrDefault(
                        ENV_FORECAST_ENDPOINT,
                        PROPERTY_FORECAST_ENDPOINT,
                        properties,
                        DEFAULT_FORECAST_ENDPOINT.toString()
                )),
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    public static Path localConfigPath() {
        return LOCAL_CONFIG_PATH;
    }

    private static Properties loadLocalProperties() {
        Properties properties = new Properties();
        if (!Files.exists(LOCAL_CONFIG_PATH)) {
            return properties;
        }

        try (var reader = Files.newBufferedReader(LOCAL_CONFIG_PATH, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException exception) {
            throw new WeatherException("读取本地天气配置失败：" + LOCAL_CONFIG_PATH, exception);
        }
    }

    private static String configValueOrDefault(
            String environmentName,
            String propertyName,
            Properties localProperties,
            String defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private static String configValue(String environmentName, String propertyName, Properties localProperties) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }

        String localValue = localProperties.getProperty(propertyName);
        if (localValue != null && !localValue.isBlank()) {
            return localValue.trim();
        }

        return null;
    }

    private static double configDouble(
            String environmentName,
            String propertyName,
            Properties localProperties,
            double defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new WeatherException(propertyName + " 必须是数字", exception);
        }
    }
}
