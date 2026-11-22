package app.weather;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public record WeatherConfig(
        double latitude,
        double longitude,
        String locationName,
        URI forecastEndpoint,
        Duration requestTimeout,
        LocationMode locationMode,
        Path locationHelper,
        Duration locationTimeout
) {
    public enum LocationMode { AUTO, FIXED }

    public static final String ENV_LATITUDE = "CLOSURE_WEATHER_LATITUDE";
    public static final String ENV_LONGITUDE = "CLOSURE_WEATHER_LONGITUDE";
    public static final String ENV_LOCATION_NAME = "CLOSURE_WEATHER_LOCATION_NAME";
    public static final String ENV_FORECAST_ENDPOINT = "CLOSURE_WEATHER_FORECAST_ENDPOINT";
    public static final String ENV_LOCATION_MODE = "CLOSURE_WEATHER_LOCATION_MODE";
    public static final String ENV_LOCATION_HELPER = "CLOSURE_WEATHER_LOCATION_HELPER";

    public static final String PROPERTY_LATITUDE = "weather.latitude";
    public static final String PROPERTY_LONGITUDE = "weather.longitude";
    public static final String PROPERTY_LOCATION_NAME = "weather.locationName";
    public static final String PROPERTY_FORECAST_ENDPOINT = "weather.forecastEndpoint";
    public static final String PROPERTY_LOCATION_MODE = "weather.locationMode";
    public static final String PROPERTY_LOCATION_HELPER = "weather.locationHelper";

    private static final Path LOCAL_CONFIG_PATH = Path.of("config", "local-weather.properties");
    private static final URI DEFAULT_FORECAST_ENDPOINT = URI.create("https://api.open-meteo.com/v1/forecast");
    private static final Path DEFAULT_LOCATION_HELPER = Path.of(System.getProperty("user.home"),
            "Library", "Application Support", "Closure", "Location", "ClosureLocation.app");
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration DEFAULT_LOCATION_TIMEOUT = Duration.ofSeconds(45);

    /** Preserve explicit fixed-location callers without changing their contract. */
    public WeatherConfig(double latitude, double longitude, String locationName, URI endpoint, Duration timeout) {
        this(latitude, longitude, locationName, endpoint, timeout,
                LocationMode.FIXED, DEFAULT_LOCATION_HELPER, DEFAULT_LOCATION_TIMEOUT);
    }

    public WeatherConfig {
        locationMode = locationMode == null ? LocationMode.AUTO : locationMode;
        if (locationName == null || locationName.isBlank()) {
            locationName = locationMode == LocationMode.AUTO ? "当前位置" : "所选位置";
        }
        forecastEndpoint = forecastEndpoint == null ? DEFAULT_FORECAST_ENDPOINT : forecastEndpoint;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        locationHelper = locationHelper == null ? DEFAULT_LOCATION_HELPER : locationHelper;
        locationTimeout = locationTimeout == null ? DEFAULT_LOCATION_TIMEOUT : locationTimeout;
        if (requestTimeout.isNegative() || requestTimeout.isZero()
                || locationTimeout.isNegative() || locationTimeout.isZero()) {
            throw new WeatherException("天气和定位超时必须大于零");
        }
        if (locationMode == LocationMode.FIXED) {
            new WeatherLocation(latitude, longitude, locationName);
        }
    }

    public static WeatherConfig fromEnvironment() {
        return from(System.getenv(), loadLocalProperties());
    }

    static WeatherConfig from(Map<String, String> environment, Properties properties) {
        String rawMode = value(ENV_LOCATION_MODE, PROPERTY_LOCATION_MODE, environment, properties, "auto");
        LocationMode mode;
        try {
            mode = LocationMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new WeatherException("weather.locationMode 只支持 auto 或 fixed");
        }
        // Legacy coordinates must not silently pin an automatic lookup to an old city.
        double latitude = mode == LocationMode.FIXED
                ? coordinate(ENV_LATITUDE, PROPERTY_LATITUDE, environment, properties) : Double.NaN;
        double longitude = mode == LocationMode.FIXED
                ? coordinate(ENV_LONGITUDE, PROPERTY_LONGITUDE, environment, properties) : Double.NaN;
        String name = mode == LocationMode.FIXED
                ? value(ENV_LOCATION_NAME, PROPERTY_LOCATION_NAME, environment, properties, "所选位置") : "当前位置";
        return new WeatherConfig(latitude, longitude, name,
                URI.create(value(ENV_FORECAST_ENDPOINT, PROPERTY_FORECAST_ENDPOINT,
                        environment, properties, DEFAULT_FORECAST_ENDPOINT.toString())),
                DEFAULT_REQUEST_TIMEOUT, mode,
                Path.of(value(ENV_LOCATION_HELPER, PROPERTY_LOCATION_HELPER,
                        environment, properties, DEFAULT_LOCATION_HELPER.toString())),
                DEFAULT_LOCATION_TIMEOUT);
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

    private static String value(String env, String property, Map<String, String> environment,
                                Properties properties, String defaultValue) {
        String result = environment.get(env);
        if (result == null || result.isBlank()) {
            result = properties.getProperty(property);
        }
        return result == null || result.isBlank() ? defaultValue : result.trim();
    }

    private static double coordinate(String env, String property, Map<String, String> environment,
                                     Properties properties) {
        String raw = value(env, property, environment, properties, null);
        if (raw == null) {
            throw new WeatherException("固定天气位置需要设置 " + property);
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            throw new WeatherException(property + " 必须是数字");
        }
    }
}
