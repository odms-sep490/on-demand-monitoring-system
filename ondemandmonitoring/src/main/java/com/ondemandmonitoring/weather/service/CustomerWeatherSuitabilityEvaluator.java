package com.ondemandmonitoring.weather.service;

import com.ondemandmonitoring.weather.config.WeatherProperties;
import com.ondemandmonitoring.weather.dto.WeatherSuitability;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Advisory suitability for the customer's chosen time. All thresholds come from
 * {@link WeatherProperties.Customer}. This is customer guidance only: it is NOT an
 * aviation safety limit and must not be reused as a flight authorisation rule.
 */
@Component
@RequiredArgsConstructor
public class CustomerWeatherSuitabilityEvaluator {

    public record Result(WeatherSuitability suitability, List<String> warnings) {
    }

    private final WeatherProperties properties;

    public Result evaluate(
            Double rainProbabilityPercent,
            Double windSpeedKmh,
            Double windGustKmh,
            Double visibilityMeters,
            Integer weatherCode) {
        WeatherProperties.Level caution = properties.getCustomer().getCaution();
        WeatherProperties.Level poor = properties.getCustomer().getPoor();

        WeatherSuitability level = WeatherSuitability.GOOD;
        List<String> warnings = new ArrayList<>();

        level = check(level, warnings, "RAIN", rainProbabilityPercent,
                caution.getRainProbabilityPercent(), poor.getRainProbabilityPercent(), true);
        level = check(level, warnings, "WIND", windSpeedKmh,
                caution.getWindSpeedKmh(), poor.getWindSpeedKmh(), true);
        level = check(level, warnings, "GUST", windGustKmh,
                caution.getWindGustKmh(), poor.getWindGustKmh(), true);
        // Visibility is the inverse: LOW values are worse.
        level = check(level, warnings, "LOW_VISIBILITY", visibilityMeters,
                caution.getVisibilityMeters(), poor.getVisibilityMeters(), false);

        if (WeatherCodeLabels.isThunderstorm(weatherCode)) {
            warnings.add("THUNDERSTORM");
            level = WeatherSuitability.POOR;
        }
        if (WeatherCodeLabels.isDrizzle(weatherCode)) {
            warnings.add("DRIZZLE");
            if (level == WeatherSuitability.GOOD) {
                level = WeatherSuitability.CAUTION;
            }
        }
        return new Result(level, List.copyOf(warnings));
    }

    /** higherIsWorse: true -> value >= threshold triggers; false -> value <= threshold triggers. */
    private static WeatherSuitability check(
            WeatherSuitability current,
            List<String> warnings,
            String code,
            Double value,
            double cautionThreshold,
            double poorThreshold,
            boolean higherIsWorse) {
        if (value == null) {
            return current;
        }
        boolean isPoor = higherIsWorse ? value >= poorThreshold : value <= poorThreshold;
        boolean isCaution = higherIsWorse ? value >= cautionThreshold : value <= cautionThreshold;
        if (isPoor) {
            warnings.add(code);
            return WeatherSuitability.POOR;
        }
        if (isCaution) {
            warnings.add(code);
            return current == WeatherSuitability.POOR ? current : WeatherSuitability.CAUTION;
        }
        return current;
    }
}
