package app.weather;

public record WeatherSnapshot(
        String locationName,
        double currentTemperature,
        double apparentTemperature,
        int currentWeatherCode,
        double currentWindSpeed,
        double dailyMaxTemperature,
        double dailyMinTemperature,
        int dailyWeatherCode,
        double precipitationProbabilityMax,
        double precipitationSum
) {
}
