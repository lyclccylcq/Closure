package app.weather;

/** One weather lookup location; never persisted as a movement history. */
public record WeatherLocation(double latitude, double longitude, String name) {
    public WeatherLocation {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new WeatherException("天气位置的经纬度无效");
        }
        if (name == null || name.isBlank() || name.length() > 120
                || name.codePoints().anyMatch(Character::isISOControl)) {
            name = "当前位置";
        } else {
            name = name.trim();
        }
    }
}
