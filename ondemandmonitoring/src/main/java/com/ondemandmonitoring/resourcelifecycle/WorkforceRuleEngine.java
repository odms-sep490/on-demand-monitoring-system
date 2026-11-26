package com.ondemandmonitoring.resourcelifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure workforce policy evaluation engine.
 * Never hardcodes 8h/day or 40h/week; all limits must come from an active WORKFORCE policy.
 */
public final class WorkforceRuleEngine {

    public record PolicyLimits(
            Long maxDailySeconds,
            Long maxWeeklySeconds,
            Long maxMonthlySeconds,
            Long minRestSeconds,
            int warningPercent,
            Set<String> requiredCertificates) {
    }

    public record Evaluation(
            String status,
            boolean blocked,
            boolean warning,
            Double maxUtilizationPercent,
            List<String> blockingReasons,
            List<String> warningReasons) {
    }

    public Evaluation evaluate(
            long projectedDailySeconds,
            long projectedWeeklySeconds,
            long projectedMonthlySeconds,
            Long restSecondsBeforeMission,
            Set<String> staffCertificates,
            PolicyLimits limits) {
        if (limits == null) {
            return new Evaluation("UNKNOWN", false, false, null, List.of(), List.of("NO_ACTIVE_WORKFORCE_POLICY"));
        }

        validateLimits(limits);

        List<String> blockingReasons = new ArrayList<>();
        List<String> warningReasons = new ArrayList<>();
        double maxUtilization = 0.0;
        boolean anyLimit = false;

        if (limits.maxDailySeconds() != null) {
            anyLimit = true;
            double ratio = 100.0 * projectedDailySeconds / limits.maxDailySeconds();
            maxUtilization = Math.max(maxUtilization, ratio);
            if (projectedDailySeconds > limits.maxDailySeconds()) {
                blockingReasons.add("DAILY_WORKLOAD_LIMIT_EXCEEDED");
            } else if (ratio >= limits.warningPercent()) {
                warningReasons.add("DAILY_WORKLOAD_NEAR_LIMIT");
            }
        }

        if (limits.maxWeeklySeconds() != null) {
            anyLimit = true;
            double ratio = 100.0 * projectedWeeklySeconds / limits.maxWeeklySeconds();
            maxUtilization = Math.max(maxUtilization, ratio);
            // Weekly/monthly overruns only warn: assignment is blocked per day (the mission's own day),
            // so a staff member who is over the week can still be scheduled on a day they have not worked.
            if (projectedWeeklySeconds > limits.maxWeeklySeconds()) {
                warningReasons.add("WEEKLY_WORKLOAD_LIMIT_EXCEEDED");
            } else if (ratio >= limits.warningPercent()) {
                warningReasons.add("WEEKLY_WORKLOAD_NEAR_LIMIT");
            }
        }

        if (limits.maxMonthlySeconds() != null) {
            anyLimit = true;
            double ratio = 100.0 * projectedMonthlySeconds / limits.maxMonthlySeconds();
            maxUtilization = Math.max(maxUtilization, ratio);
            if (projectedMonthlySeconds > limits.maxMonthlySeconds()) {
                warningReasons.add("MONTHLY_WORKLOAD_LIMIT_EXCEEDED");
            } else if (ratio >= limits.warningPercent()) {
                warningReasons.add("MONTHLY_WORKLOAD_NEAR_LIMIT");
            }
        }

        if (limits.minRestSeconds() != null && restSecondsBeforeMission != null
                && restSecondsBeforeMission < limits.minRestSeconds()) {
            blockingReasons.add("INSUFFICIENT_REST_TIME");
        }

        // Certificates are intentionally not a workforce assignment constraint; only time-based rules apply.

        boolean blocked = !blockingReasons.isEmpty();
        boolean warning = !blocked && !warningReasons.isEmpty();
        String status = blocked
                ? "BLOCKED"
                : warning
                        ? "WARNING"
                        : anyLimit ? "ELIGIBLE" : "UNKNOWN";

        return new Evaluation(
                status,
                blocked,
                warning,
                anyLimit ? maxUtilization : null,
                List.copyOf(blockingReasons),
                List.copyOf(warningReasons));
    }

    private void validateLimits(PolicyLimits limits) {
        if (limits.warningPercent() <= 0 || limits.warningPercent() >= 100) {
            throw new IllegalArgumentException("Workforce warning percentage must be between 1 and 99");
        }
        if (limits.maxDailySeconds() != null && limits.maxDailySeconds() <= 0L) {
            throw new IllegalArgumentException("Daily limit must be positive");
        }
        if (limits.maxWeeklySeconds() != null && limits.maxWeeklySeconds() <= 0L) {
            throw new IllegalArgumentException("Weekly limit must be positive");
        }
        if (limits.maxMonthlySeconds() != null && limits.maxMonthlySeconds() <= 0L) {
            throw new IllegalArgumentException("Monthly limit must be positive");
        }
        if (limits.minRestSeconds() != null && limits.minRestSeconds() < 0L) {
            throw new IllegalArgumentException("Minimum rest seconds cannot be negative");
        }
    }
}
