package com.ondemandmonitoring.resourcelifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageCalculationTest {

    private static final Instant BASE = Instant.parse("2026-11-25T00:00:00Z");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void altitudeAloneIsNotActualFlight() {
        List<FlightTimeCalculator.Sample> samples = List.of(
                new FlightTimeCalculator.Sample(BASE, null, null, true, 10.0, 106.0, 50.0),
                new FlightTimeCalculator.Sample(BASE.plusSeconds(30), null, null, true, 10.0, 106.0, 50.0));

        FlightTimeCalculator.Result result = new FlightTimeCalculator()
                .calculate(samples, BASE, BASE.plusSeconds(60));

        assertEquals("ASSIGNMENT_ESTIMATED", result.source());
        assertEquals(60L, result.estimatedSeconds());
        assertEquals(0L, result.actualSeconds());
        assertTrue(result.warnings().contains("AIRBORNE_DURATION_UNVERIFIED"));
    }

    @Test
    void actualSignalsHandleDuplicatesOutOfOrderMultipleSortiesAndDistance() {
        List<FlightTimeCalculator.Sample> samples = List.of(
                sample(30, true, true, 10.0002, 106.0000),
                sample(0, false, false, 10.0000, 106.0000),
                sample(0, false, false, 10.0000, 106.0000),
                sample(10, true, true, 10.0001, 106.0000),
                sample(40, false, false, 10.0002, 106.0000),
                sample(50, true, true, 10.0003, 106.0000),
                sample(60, false, false, 10.0004, 106.0000));

        FlightTimeCalculator.Result result = new FlightTimeCalculator()
                .calculate(samples, BASE, BASE.plusSeconds(60));

        assertEquals(40L, result.actualSeconds());
        assertEquals("TELEMETRY_ACTUAL", result.source());
        assertEquals(2, result.takeoffs());
        assertEquals(2, result.landings());
        assertNotNull(result.measuredDistanceMeters());
        assertTrue(result.measuredDistanceMeters() > 0.0);
    }

    @Test
    void conflictingTelemetryAtSameTimestampIsDiscardedWithWarning() {
        List<FlightTimeCalculator.Sample> samples = List.of(
                sample(0, true, true, 10.0, 106.0),
                sample(30, true, true, 10.0, 106.0),
                sample(30, false, false, 10.0, 106.0),
                sample(60, false, false, 10.0, 106.0));

        FlightTimeCalculator.Result result = new FlightTimeCalculator()
                .calculate(samples, BASE, BASE.plusSeconds(60));

        assertTrue(result.warnings().contains("CONFLICTING_TELEMETRY"));
    }

    @Test
    void largeGapsAreUnknownNotActual() {
        List<FlightTimeCalculator.Sample> samples = List.of(
                sample(0, true, true, 10.0, 106.0),
                sample(300, false, false, 10.0, 106.0));

        FlightTimeCalculator.Result result = new FlightTimeCalculator()
                .calculate(samples, BASE, BASE.plusSeconds(300));

        assertEquals(0L, result.actualSeconds());
        assertEquals(300L, result.estimatedSeconds());
        assertTrue(result.warnings().contains("TELEMETRY_GAP"));
    }

    @Test
    void gpsJumpExcludesUnrealisticDistance() {
        List<FlightTimeCalculator.Sample> samples = List.of(
                sample(0, true, true, 10.0, 106.0),
                sample(10, true, true, 11.0, 107.0));

        FlightTimeCalculator.Result result = new FlightTimeCalculator()
                .calculate(samples, BASE, BASE.plusSeconds(10));

        assertEquals(10L, result.actualSeconds());
        assertNull(result.measuredDistanceMeters());
        assertTrue(result.warnings().contains("GPS_JUMP"));
    }

    @Test
    void actualFlightCannotLeakOutsideAssignment() {
        List<FlightTimeCalculator.Sample> samples = List.of(
                sample(0, true, true, 10.0, 106.0),
                sample(30, true, true, 10.0, 106.0),
                sample(60, false, false, 10.0, 106.0));

        FlightTimeCalculator.Result result = new FlightTimeCalculator()
                .calculate(samples, BASE.plusSeconds(10), BASE.plusSeconds(50));

        assertEquals(40L, result.actualSeconds());
        assertEquals("TELEMETRY_ACTUAL", result.source());
    }

    @Test
    void unionsOverlapAndSplitsLocalMidnightAndWeekMonthBuckets() {
        Instant startA = BASE.plusSeconds(16 * 3600L); // 2026-11-25 23:00+07
        Instant endA = BASE.plusSeconds(18 * 3600L);   // 2026-11-26 01:00+07
        List<WorkTimeCalculator.Span> spans = List.of(
                new WorkTimeCalculator.Span(startA, endA),
                new WorkTimeCalculator.Span(startA.plusSeconds(1800L), endA));

        WorkTimeCalculator calculator = new WorkTimeCalculator();
        Map<LocalDate, Long> daily = calculator.dailySeconds(spans, VN_ZONE);

        assertEquals(3600L, daily.get(LocalDate.parse("2026-11-25")));
        assertEquals(3600L, daily.get(LocalDate.parse("2026-11-26")));
        assertEquals(7200L, calculator.weeklySeconds(spans, LocalDate.parse("2026-11-25"), VN_ZONE));
        assertEquals(7200L, calculator.monthlySeconds(spans, YearMonth.of(2026, 11), VN_ZONE));
        assertEquals(1800L, calculator.restSecondsBefore(spans, endA.plusSeconds(1800L)));
    }

    private FlightTimeCalculator.Sample sample(
            long offsetSeconds, boolean airborne, boolean motorsActive, double lat, double lon) {
        return new FlightTimeCalculator.Sample(
                BASE.plusSeconds(offsetSeconds),
                airborne,
                motorsActive,
                true,
                lat,
                lon,
                10.0);
    }
}
