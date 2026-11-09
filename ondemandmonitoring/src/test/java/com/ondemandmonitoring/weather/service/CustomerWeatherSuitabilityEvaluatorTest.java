package com.ondemandmonitoring.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ondemandmonitoring.weather.config.WeatherProperties;
import com.ondemandmonitoring.weather.dto.WeatherSuitability;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerWeatherSuitabilityEvaluatorTest {

    private final CustomerWeatherSuitabilityEvaluator evaluator =
            new CustomerWeatherSuitabilityEvaluator(new WeatherProperties());

    @Test
    void goodWhenEverythingBelowCautionThresholds() {
        var result = evaluator.evaluate(10.0, 8.0, 12.0, 20000.0, 0);
        assertEquals(WeatherSuitability.GOOD, result.suitability());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void cautionWhenRainAndWindReachCautionLevel() {
        var result = evaluator.evaluate(55.0, 24.0, 28.0, 10000.0, 61);
        assertEquals(WeatherSuitability.CAUTION, result.suitability());
        assertEquals(List.of("RAIN", "WIND"), result.warnings());
    }

    @Test
    void cautionIsInclusiveAtTheThreshold() {
        assertEquals(WeatherSuitability.CAUTION, evaluator.evaluate(40.0, 0.0, 0.0, 20000.0, 0).suitability());
        assertEquals(WeatherSuitability.CAUTION, evaluator.evaluate(0.0, 0.0, 0.0, 3000.0, 0).suitability());
    }

    @Test
    void poorWhenRainWindAndGustReachPoorLevel() {
        var result = evaluator.evaluate(85.0, 38.0, 50.0, 10000.0, 65);
        assertEquals(WeatherSuitability.POOR, result.suitability());
        assertEquals(List.of("RAIN", "WIND", "GUST"), result.warnings());
    }

    @Test
    void lowVisibilityIsEvaluatedInReverse() {
        assertEquals(WeatherSuitability.CAUTION, evaluator.evaluate(0.0, 0.0, 0.0, 2500.0, 3).suitability());
        assertEquals(WeatherSuitability.POOR, evaluator.evaluate(0.0, 0.0, 0.0, 800.0, 45).suitability());
    }

    @Test
    void poorLevelIsNotDowngradedByALaterCautionSignal() {
        // rain is POOR, gust only CAUTION -> still POOR
        var result = evaluator.evaluate(90.0, 5.0, 32.0, 20000.0, 0);
        assertEquals(WeatherSuitability.POOR, result.suitability());
        assertTrue(result.warnings().containsAll(List.of("RAIN", "GUST")));
    }

    @Test
    void thunderstormIsPoorEvenWithCalmNumbers() {
        var result = evaluator.evaluate(10.0, 5.0, 8.0, 20000.0, 95);
        assertEquals(WeatherSuitability.POOR, result.suitability());
        assertEquals(List.of("THUNDERSTORM"), result.warnings());
    }

    @Test
    void drizzleIsNeverMarkedGoodEvenWhenOtherMetricsAreFavorable() {
        for (int weatherCode : List.of(51, 53, 55, 56, 57)) {
            var result = evaluator.evaluate(10.0, 8.0, 12.0, 20000.0, weatherCode);
            assertEquals(WeatherSuitability.CAUTION, result.suitability());
            assertEquals(List.of("DRIZZLE"), result.warnings());
        }
    }

    @Test
    void missingValuesAreIgnored() {
        var result = evaluator.evaluate(null, null, null, null, null);
        assertEquals(WeatherSuitability.GOOD, result.suitability());
    }

    @Test
    void thresholdsComeFromConfiguration() {
        WeatherProperties strict = new WeatherProperties();
        strict.getCustomer().getCaution().setRainProbabilityPercent(10);
        var result = new CustomerWeatherSuitabilityEvaluator(strict).evaluate(15.0, 0.0, 0.0, 20000.0, 0);
        assertEquals(WeatherSuitability.CAUTION, result.suitability());
    }
}
