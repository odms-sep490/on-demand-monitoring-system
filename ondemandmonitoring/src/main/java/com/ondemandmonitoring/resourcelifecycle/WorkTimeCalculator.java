package com.ondemandmonitoring.resourcelifecycle;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure work-time calculator that unions overlapping intervals before splitting across
 * local calendar boundaries (default Asia/Ho_Chi_Minh).
 */
public final class WorkTimeCalculator {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public record Span(Instant start, Instant end) {
    }

    public List<Span> union(List<Span> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<Span> sorted = input.stream()
                .filter(span -> span != null
                        && span.start() != null
                        && span.end() != null
                        && span.start().isBefore(span.end()))
                .sorted(Comparator.comparing(Span::start))
                .toList();

        List<Span> merged = new ArrayList<>();
        for (Span span : sorted) {
            if (merged.isEmpty() || merged.getLast().end().isBefore(span.start())) {
                merged.add(span);
            } else {
                Span previous = merged.removeLast();
                Instant extendedEnd = previous.end().isAfter(span.end()) ? previous.end() : span.end();
                merged.add(new Span(previous.start(), extendedEnd));
            }
        }
        return List.copyOf(merged);
    }

    public Map<LocalDate, Long> dailySeconds(List<Span> input, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? DEFAULT_ZONE : zone;
        Map<LocalDate, Long> totalsByDate = new TreeMap<>();
        for (Span span : union(input)) {
            Instant cursor = span.start();
            while (cursor.isBefore(span.end())) {
                LocalDate localDate = cursor.atZone(effectiveZone).toLocalDate();
                Instant nextMidnight = localDate.plusDays(1).atStartOfDay(effectiveZone).toInstant();
                Instant segmentEnd = nextMidnight.isBefore(span.end()) ? nextMidnight : span.end();
                long segmentSeconds = Duration.between(cursor, segmentEnd).getSeconds();
                totalsByDate.merge(localDate, segmentSeconds, Long::sum);
                cursor = segmentEnd;
            }
        }
        return totalsByDate;
    }

    public long weeklySeconds(List<Span> input, LocalDate referenceDate, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? DEFAULT_ZONE : zone;
        LocalDate weekStart = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEndExclusive = weekStart.plusDays(7);
        Instant startInstant = weekStart.atStartOfDay(effectiveZone).toInstant();
        Instant endInstant = weekEndExclusive.atStartOfDay(effectiveZone).toInstant();
        return secondsWithin(input, startInstant, endInstant);
    }

    public long monthlySeconds(List<Span> input, YearMonth month, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? DEFAULT_ZONE : zone;
        Instant startInstant = month.atDay(1).atStartOfDay(effectiveZone).toInstant();
        Instant endInstant = month.plusMonths(1).atDay(1).atStartOfDay(effectiveZone).toInstant();
        return secondsWithin(input, startInstant, endInstant);
    }

    public long yearlySeconds(List<Span> input, int year, ZoneId zone) {
        ZoneId effectiveZone = zone == null ? DEFAULT_ZONE : zone;
        Instant startInstant = LocalDate.of(year, 1, 1).atStartOfDay(effectiveZone).toInstant();
        Instant endInstant = LocalDate.of(year + 1, 1, 1).atStartOfDay(effectiveZone).toInstant();
        return secondsWithin(input, startInstant, endInstant);
    }

    public long secondsWithin(List<Span> input, Instant windowStart, Instant windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            return 0L;
        }
        return union(input).stream()
                .map(span -> new Span(
                        span.start().isBefore(windowStart) ? windowStart : span.start(),
                        span.end().isAfter(windowEnd) ? windowEnd : span.end()))
                .filter(span -> span.start().isBefore(span.end()))
                .mapToLong(span -> Duration.between(span.start(), span.end()).getSeconds())
                .sum();
    }

    /**
     * Returns the rest duration in seconds between the nearest prior work span ending on or before
     * {@code targetStart}, or {@code null} if no prior work span exists.
     */
    public Long restSecondsBefore(List<Span> existingSpans, Instant targetStart) {
        if (targetStart == null || existingSpans == null || existingSpans.isEmpty()) {
            return null;
        }
        Instant latestEnd = null;
        for (Span span : union(existingSpans)) {
            if (!span.end().isAfter(targetStart)) {
                if (latestEnd == null || span.end().isAfter(latestEnd)) {
                    latestEnd = span.end();
                }
            }
        }
        return latestEnd == null ? null : Duration.between(latestEnd, targetStart).getSeconds();
    }
}
