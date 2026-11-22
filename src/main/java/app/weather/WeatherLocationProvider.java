package app.weather;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface WeatherLocationProvider {
    CompletableFuture<WeatherLocation> currentLocation();
}
