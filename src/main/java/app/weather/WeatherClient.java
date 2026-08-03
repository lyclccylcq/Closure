package app.weather;

import java.util.concurrent.CompletableFuture;

public interface WeatherClient {
    CompletableFuture<WeatherSnapshot> currentWeather();
}
