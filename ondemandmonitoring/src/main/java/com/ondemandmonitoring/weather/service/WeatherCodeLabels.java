package com.ondemandmonitoring.weather.service;

/** Maps WMO weather codes (as used by Open-Meteo) to short Vietnamese customer labels. */
public final class WeatherCodeLabels {

    private WeatherCodeLabels() {
    }

    public static String label(Integer code) {
        if (code == null) {
            return "Không rõ";
        }
        return switch (code) {
            case 0 -> "Trời quang";
            case 1 -> "Ít mây";
            case 2 -> "Có mây";
            case 3 -> "Nhiều mây";
            case 45, 48 -> "Có sương mù";
            case 51, 53, 55, 56, 57 -> "Mưa phùn";
            case 61, 80 -> "Mưa nhẹ";
            case 63, 66, 81 -> "Mưa";
            case 65, 67, 82 -> "Mưa lớn";
            case 71, 73, 75, 77, 85, 86 -> "Có tuyết";
            case 95, 96, 99 -> "Dông";
            default -> "Có mây";
        };
    }

    public static boolean isThunderstorm(Integer code) {
        return code != null && (code == 95 || code == 96 || code == 99);
    }

    /** WMO drizzle codes: light, persistent precipitation still needs caution for a flight. */
    public static boolean isDrizzle(Integer code) {
        return code != null && (code == 51 || code == 53 || code == 55 || code == 56 || code == 57);
    }
}
