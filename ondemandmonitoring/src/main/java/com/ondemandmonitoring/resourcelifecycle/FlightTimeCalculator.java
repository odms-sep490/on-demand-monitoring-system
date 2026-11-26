package com.ondemandmonitoring.resourcelifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure evidence-based flight time calculator.
 * Altitude alone never establishes airborne state; motor and airborne signals must corroborate.
 */
public final class FlightTimeCalculator {

    private static final long MAX_SAMPLE_GAP_MILLIS = 60_000L;
    private static final double MAX_REALISTIC_SPEED_MPS = 100.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    public record Sample(
            Instant recordedAt,
            Boolean airborne,
            Boolean motorsActive,
            Boolean connected,
            Double latitude,
            Double longitude,
            Double relativeAltitudeMeters) {
    }

    public record Result(
            long actualSeconds,
            long estimatedSeconds,
            long motorSeconds,
            int takeoffs,
            int landings,
            Double measuredDistanceMeters,
            String source,
            double completeness,
            List<String> warnings) {
    }

    public Result calculate(List<Sample> samples, Instant intervalStart, Instant intervalEnd) {
        if (intervalStart == null || intervalEnd == null || !intervalStart.isBefore(intervalEnd)) {
            return new Result(0L, 0L, 0L, 0, 0, null, "UNKNOWN", 0.0, List.of("INVALID_INTERVAL"));
        }

        Set<String> warnings = new LinkedHashSet<>();
        List<Sample> orderedSamples = deduplicateAndSort(samples, warnings);

        long actualSeconds = 0L;
        long coveredSeconds = 0L;
        long motorSeconds = 0L;
        int takeoffs = 0;
        int landings = 0;
        double totalDistanceMeters = 0.0;
        boolean distanceMeasured = false;

        for (int index = 1; index < orderedSamples.size(); index++) {
            Sample previous = orderedSamples.get(index - 1);
            Sample current = orderedSamples.get(index);

            if (!previous.recordedAt().isBefore(intervalEnd) || !current.recordedAt().isAfter(intervalStart)) {
                continue;
            }

            long gapMillis = Duration.between(previous.recordedAt(), current.recordedAt()).toMillis();
            if (gapMillis <= 0L || gapMillis > MAX_SAMPLE_GAP_MILLIS) {
                warnings.add("TELEMETRY_GAP");
                continue;
            }

            Instant clippedStart = previous.recordedAt().isBefore(intervalStart)
                    ? intervalStart
                    : previous.recordedAt();
            Instant clippedEnd = current.recordedAt().isAfter(intervalEnd)
                    ? intervalEnd
                    : current.recordedAt();
            long segmentSeconds = Duration.between(clippedStart, clippedEnd).getSeconds();

            if (!hasCorroboratedSignals(previous, current)) {
                continue;
            }

            if (Boolean.TRUE.equals(previous.airborne()) && !Boolean.TRUE.equals(previous.motorsActive())) {
                warnings.add("INCONSISTENT_FLIGHT_STATE");
                continue;
            }

            coveredSeconds += segmentSeconds;
            if (Boolean.TRUE.equals(previous.motorsActive())) {
                motorSeconds += segmentSeconds;
            }

            if (Boolean.TRUE.equals(previous.airborne()) && Boolean.TRUE.equals(previous.motorsActive())) {
                actualSeconds += segmentSeconds;
                if (hasValidGps(previous)
                        && hasValidGps(current)
                        && clippedStart.equals(previous.recordedAt())
                        && clippedEnd.equals(current.recordedAt())) {
                    double segmentDistance = haversineMeters(previous, current);
                    double speedMps = segmentDistance / (gapMillis / 1000.0);
                    if (speedMps <= MAX_REALISTIC_SPEED_MPS) {
                        totalDistanceMeters += segmentDistance;
                        distanceMeasured = true;
                    } else {
                        warnings.add("GPS_JUMP");
                    }
                }
            }

            if (!current.recordedAt().isBefore(intervalStart) && !current.recordedAt().isAfter(intervalEnd)) {
                if (!Boolean.TRUE.equals(previous.airborne())
                        && Boolean.TRUE.equals(current.airborne())
                        && Boolean.TRUE.equals(current.motorsActive())) {
                    takeoffs++;
                }
                if (Boolean.TRUE.equals(previous.airborne()) && !Boolean.TRUE.equals(current.airborne())) {
                    landings++;
                }
            }
        }

        long totalIntervalSeconds = Duration.between(intervalStart, intervalEnd).getSeconds();
        long unverifiedSeconds = Math.max(0L, totalIntervalSeconds - coveredSeconds);
        String source = determineSource(coveredSeconds, totalIntervalSeconds);
        if (unverifiedSeconds > 0L) {
            warnings.add("AIRBORNE_DURATION_UNVERIFIED");
        }

        double completeness = totalIntervalSeconds > 0L
                ? (double) coveredSeconds / (double) totalIntervalSeconds
                : 0.0;
        Double distanceResult = distanceMeasured ? totalDistanceMeters : null;

        return new Result(
                actualSeconds,
                unverifiedSeconds,
                motorSeconds,
                takeoffs,
                landings,
                distanceResult,
                source,
                completeness,
                List.copyOf(warnings));
    }

    private List<Sample> deduplicateAndSort(List<Sample> samples, Set<String> warnings) {
        TreeMap<Instant, Sample> samplesByTime = new TreeMap<>();
        Set<Instant> conflictingTimestamps = new HashSet<>();

        if (samples != null) {
            for (Sample sample : samples) {
                if (sample == null || sample.recordedAt() == null) {
                    continue;
                }
                Sample existing = samplesByTime.putIfAbsent(sample.recordedAt(), sample);
                if (existing != null && !existing.equals(sample)) {
                    conflictingTimestamps.add(sample.recordedAt());
                }
            }
        }

        if (!conflictingTimestamps.isEmpty()) {
            conflictingTimestamps.forEach(samplesByTime::remove);
            warnings.add("CONFLICTING_TELEMETRY");
        }
        return new ArrayList<>(samplesByTime.values());
    }

    private boolean hasCorroboratedSignals(Sample previous, Sample current) {
        return Boolean.TRUE.equals(previous.connected())
                && Boolean.TRUE.equals(current.connected())
                && previous.airborne() != null
                && previous.motorsActive() != null
                && current.airborne() != null
                && current.motorsActive() != null;
    }

    private String determineSource(long coveredSeconds, long totalIntervalSeconds) {
        if (coveredSeconds == totalIntervalSeconds && totalIntervalSeconds > 0L) {
            return "TELEMETRY_ACTUAL";
        }
        if (coveredSeconds > 0L) {
            return "MIXED";
        }
        return "ASSIGNMENT_ESTIMATED";
    }

    private boolean hasValidGps(Sample sample) {
        return sample.latitude() != null
                && sample.longitude() != null
                && Double.isFinite(sample.latitude())
                && Double.isFinite(sample.longitude())
                && Math.abs(sample.latitude()) <= 90.0
                && Math.abs(sample.longitude()) <= 180.0;
    }

    private double haversineMeters(Sample first, Sample second) {
        double deltaLat = Math.toRadians(second.latitude() - first.latitude());
        double deltaLon = Math.toRadians(second.longitude() - first.longitude());
        double sinLat = Math.sin(deltaLat / 2.0);
        double sinLon = Math.sin(deltaLon / 2.0);
        double haversine = (sinLat * sinLat)
                + Math.cos(Math.toRadians(first.latitude()))
                * Math.cos(Math.toRadians(second.latitude()))
                * (sinLon * sinLon);
        double centralAngle = 2.0 * Math.asin(Math.sqrt(Math.min(1.0, haversine)));
        return EARTH_RADIUS_METERS * centralAngle;
    }
}
