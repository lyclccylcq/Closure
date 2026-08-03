package app.weather;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenMeteoWeatherClient implements WeatherClient {
    private static final String CURRENT_VARIABLES = String.join(",",
            "temperature_2m",
            "apparent_temperature",
            "weather_code",
            "wind_speed_10m",
            "precipitation"
    );
    private static final String DAILY_VARIABLES = String.join(",",
            "weather_code",
            "temperature_2m_max",
            "temperature_2m_min",
            "precipitation_probability_max",
            "precipitation_sum",
            "wind_speed_10m_max"
    );

    private final WeatherConfig config;
    private final HttpClient httpClient;

    public OpenMeteoWeatherClient(WeatherConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(config, "config").requestTimeout())
                .build());
    }

    OpenMeteoWeatherClient(WeatherConfig config, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public CompletableFuture<WeatherSnapshot> currentWeather() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(forecastUri())
                .timeout(config.requestTimeout())
                .GET()
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::requireOkResponse)
                .thenApply(this::parseSnapshot);
    }

    URI forecastUri() {
        String query = "latitude=" + config.latitude()
                + "&longitude=" + config.longitude()
                + "&current=" + urlEncode(CURRENT_VARIABLES)
                + "&daily=" + urlEncode(DAILY_VARIABLES)
                + "&timezone=auto"
                + "&forecast_days=1";
        String separator = config.forecastEndpoint().toString().contains("?") ? "&" : "?";
        return URI.create(config.forecastEndpoint() + separator + query);
    }

    WeatherSnapshot parseSnapshot(String json) {
        String currentObject = object(json, "current");
        String dailyObject = object(json, "daily");

        return new WeatherSnapshot(
                config.locationName(),
                numberField(currentObject, "temperature_2m"),
                numberField(currentObject, "apparent_temperature"),
                (int) numberField(currentObject, "weather_code"),
                numberField(currentObject, "wind_speed_10m"),
                firstArrayNumber(dailyObject, "temperature_2m_max"),
                firstArrayNumber(dailyObject, "temperature_2m_min"),
                (int) firstArrayNumber(dailyObject, "weather_code"),
                firstArrayNumber(dailyObject, "precipitation_probability_max"),
                firstArrayNumber(dailyObject, "precipitation_sum")
        );
    }

    private String requireOkResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new WeatherException("天气接口返回 HTTP " + response.statusCode());
        }

        return response.body();
    }

    private static String object(String json, String fieldName) {
        int fieldIndex = json.indexOf("\"" + fieldName + "\"");
        if (fieldIndex < 0) {
            throw new WeatherException("天气响应缺少字段：" + fieldName);
        }

        int start = json.indexOf('{', fieldIndex);
        if (start < 0) {
            throw new WeatherException("天气响应字段格式错误：" + fieldName);
        }

        int depth = 0;
        for (int index = start; index < json.length(); index++) {
            char character = json.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, index + 1);
                }
            }
        }

        throw new WeatherException("天气响应字段未闭合：" + fieldName);
    }

    private static double numberField(String json, String fieldName) {
        Matcher matcher = Pattern
                .compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(json);
        if (!matcher.find()) {
            throw new WeatherException("天气响应缺少数值字段：" + fieldName);
        }

        return Double.parseDouble(matcher.group(1));
    }

    private static double firstArrayNumber(String json, String fieldName) {
        Matcher matcher = Pattern
                .compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\\[\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(json);
        if (!matcher.find()) {
            throw new WeatherException("天气响应缺少数组字段：" + fieldName);
        }

        return Double.parseDouble(matcher.group(1));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
