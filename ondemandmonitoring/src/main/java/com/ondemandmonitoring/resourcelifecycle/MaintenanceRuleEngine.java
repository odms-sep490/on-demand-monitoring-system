package com.ondemandmonitoring.resourcelifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure maintenance policy evaluation engine.
 * Missing verified usage or missing policy always evaluates to UNKNOWN rather than HEALTHY.
 */
public final class MaintenanceRuleEngine {

    public record Health(
            String status,
            boolean blocked,
            Double utilization,
            Long remainingSeconds,
            Integer remainingCycles,
            Integer remainingDays,
            List<String> reasons) {

        public boolean exceedsBudget(long projectedSeconds, int projectedCycles, int projectedDays) {
            if (blocked) {
                return true;
            }
            if (remainingSeconds != null && projectedSeconds > remainingSeconds) {
                return true;
            }
            if (remainingCycles != null && projectedCycles > remainingCycles) {
                return true;
            }
            return remainingDays != null && projectedDays > remainingDays;
        }
    }

    public Health evaluate(
            Long verifiedSeconds,
            int cyclesSinceMaintenance,
            int daysSinceMaintenance,
            Long secondsLimit,
            Integer cycleLimit,
            Integer dayLimit,
            int monitorPercent,
            int warningPercent) {
        return evaluate(
                verifiedSeconds,
                cyclesSinceMaintenance,
                daysSinceMaintenance,
                0,
                0,
                secondsLimit,
                cycleLimit,
                dayLimit,
                null,
                null,
                monitorPercent,
                warningPercent);
    }

    public Health evaluate(
            Long verifiedSeconds,
            int cyclesSinceMaintenance,
            int daysSinceMaintenance,
            int repeatedPreflightFailures,
            int repeatedIncidents,
            Long secondsLimit,
            Integer cycleLimit,
            Integer dayLimit,
            Integer maxPreflightFailures,
            Integer maxIncidents,
            int monitorPercent,
            int warningPercent) {
        validateThresholds(
                secondsLimit,
                cycleLimit,
                dayLimit,
                maxPreflightFailures,
                maxIncidents,
                monitorPercent,
                warningPercent);

        boolean hasAnyLimit = secondsLimit != null
                || cycleLimit != null
                || dayLimit != null
                || maxPreflightFailures != null
                || maxIncidents != null;

        if (verifiedSeconds == null || !hasAnyLimit) {
            return new Health(
                    "UNKNOWN",
                    false,
                    null,
                    null,
                    null,
                    null,
                    List.of("VERIFIED_USAGE_OR_POLICY_MISSING"));
        }

        double maxUtilization = 0.0;
        List<String> reasons = new ArrayList<>();

        if (secondsLimit != null) {
            double ratio = 100.0 * verifiedSeconds / secondsLimit;
            maxUtilization = Math.max(maxUtilization, ratio);
            if (verifiedSeconds >= secondsLimit) {
                reasons.add("FLIGHT_HOURS_DUE");
            }
        }
        if (cycleLimit != null) {
            double ratio = 100.0 * cyclesSinceMaintenance / cycleLimit;
            maxUtilization = Math.max(maxUtilization, ratio);
            if (cyclesSinceMaintenance >= cycleLimit) {
                reasons.add("FLIGHT_CYCLES_DUE");
            }
        }
        if (dayLimit != null) {
            double ratio = 100.0 * daysSinceMaintenance / dayLimit;
            maxUtilization = Math.max(maxUtilization, ratio);
            if (daysSinceMaintenance >= dayLimit) {
                reasons.add("CALENDAR_DUE");
            }
        }
        if (maxPreflightFailures != null) {
            double ratio = 100.0 * repeatedPreflightFailures / maxPreflightFailures;
            maxUtilization = Math.max(maxUtilization, ratio);
            if (repeatedPreflightFailures >= maxPreflightFailures) {
                reasons.add("REPEATED_PREFLIGHT_FAILURES_DUE");
            }
        }
        if (maxIncidents != null) {
            double ratio = 100.0 * repeatedIncidents / maxIncidents;
            maxUtilization = Math.max(maxUtilization, ratio);
            if (repeatedIncidents >= maxIncidents) {
                reasons.add("REPEATED_INCIDENTS_DUE");
            }
        }

        String status = resolveStatus(maxUtilization, monitorPercent, warningPercent);
        Long remainingSeconds = secondsLimit == null ? null : Math.max(0L, secondsLimit - verifiedSeconds);
        Integer remainingCycles = cycleLimit == null ? null : Math.max(0, cycleLimit - cyclesSinceMaintenance);
        Integer remainingDays = dayLimit == null ? null : Math.max(0, dayLimit - daysSinceMaintenance);

        return new Health(
                status,
                maxUtilization >= 100.0,
                maxUtilization,
                remainingSeconds,
                remainingCycles,
                remainingDays,
                List.copyOf(reasons));
    }

    private void validateThresholds(
            Long secondsLimit,
            Integer cycleLimit,
            Integer dayLimit,
            Integer maxPreflightFailures,
            Integer maxIncidents,
            int monitorPercent,
            int warningPercent) {
        if (monitorPercent < 0 || warningPercent <= monitorPercent || warningPercent >= 100) {
            throw new IllegalArgumentException("Invalid maintenance monitor/warning percentages");
        }
        if (secondsLimit != null && secondsLimit <= 0L) {
            throw new IllegalArgumentException("Flight seconds limit must be positive");
        }
        if (cycleLimit != null && cycleLimit <= 0) {
            throw new IllegalArgumentException("Cycle limit must be positive");
        }
        if (dayLimit != null && dayLimit <= 0) {
            throw new IllegalArgumentException("Calendar day limit must be positive");
        }
        if (maxPreflightFailures != null && maxPreflightFailures <= 0) {
            throw new IllegalArgumentException("Max preflight failures must be positive");
        }
        if (maxIncidents != null && maxIncidents <= 0) {
            throw new IllegalArgumentException("Max incidents must be positive");
        }
    }

    private String resolveStatus(double utilizationPercent, int monitorPercent, int warningPercent) {
        if (utilizationPercent >= 100.0) {
            return "MAINTENANCE_REQUIRED";
        }
        if (utilizationPercent >= warningPercent) {
            return "MAINTENANCE_DUE_SOON";
        }
        if (utilizationPercent >= monitorPercent) {
            return "MONITOR";
        }
        return "HEALTHY";
    }
}
