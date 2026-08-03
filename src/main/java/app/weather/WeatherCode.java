package app.weather;

public final class WeatherCode {
    private WeatherCode() {
    }

    public static String describe(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大致晴朗";
            case 2 -> "局部多云";
            case 3 -> "阴";
            case 45, 48 -> "有雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61, 63, 65 -> "下雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75 -> "下雪";
            case 77 -> "雪粒";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷雨";
            case 96, 99 -> "雷雨夹冰雹";
            default -> "天气变化不明";
        };
    }

    public static boolean isWetWeather(int code) {
        return switch (code) {
            case 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> true;
            default -> false;
        };
    }
}
