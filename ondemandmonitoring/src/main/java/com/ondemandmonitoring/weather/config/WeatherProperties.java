package com.ondemandmonitoring.weather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Customer-facing weather forecast settings (prefix {@code weather}).
 * Defaults live here so the feature works without any yaml/env entry.
 */
@Data
@Component
@ConfigurationProperties(prefix = "weather")
public class WeatherProperties {

    private OpenMeteo openMeteo = new OpenMeteo();
    private Cache cache = new Cache();
    private Customer customer = new Customer();

    @Data
    public static class OpenMeteo {
        private String baseUrl = "https://api.open-meteo.com";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 4000;
        /** Open-Meteo serves at most 16 forecast days (today included). */
        private int maxForecastDays = 16;
    }

    @Data
    public static class Cache {
        private boolean enabled = true;
        private int ttlMinutes = 30;
        private int maxEntries = 500;
    }

    /** Advisory thresholds for CUSTOMER guidance only - not aviation safety limits. */
    @Data
    public static class Customer {
        private Level caution = new Level(40, 20, 30, 3000);
        private Level poor = new Level(70, 35, 50, 1000);
    }

    @Data
    public static class Level {
        private double rainProbabilityPercent;
        private double windSpeedKmh;
        private double windGustKmh;
        /** Visibility at or below this value triggers the level. */
        private double visibilityMeters;

        public Level() {
        }

        public Level(double rain, double wind, double gust, double visibility) {
            this.rainProbabilityPercent = rain;
            this.windSpeedKmh = wind;
            this.windGustKmh = gust;
            this.visibilityMeters = visibility;
        }
    }
}
