package com.ondemandmonitoring.resourcelifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    @Test
    void noHistoryCannotBeHealthy() {
        MaintenanceRuleEngine.Health health = new MaintenanceRuleEngine()
                .evaluate(null, 0, 0, null, null, null, 60, 80);

        assertEquals("UNKNOWN", health.status());
        assertFalse(health.blocked());
    }

    @Test
    void firstThresholdWinsAndBlocksAt51Of50Hours() {
        MaintenanceRuleEngine.Health health = new MaintenanceRuleEngine()
                .evaluate(51L * 3600L, 2, 10, 50L * 3600L, 100, 90, 60, 80);

        assertEquals("MAINTENANCE_REQUIRED", health.status());
        assertTrue(health.blocked());
        assertTrue(health.reasons().contains("FLIGHT_HOURS_DUE"));
    }

    @Test
    void projectedConsumptionCannotExceedRemainingBudget() {
        MaintenanceRuleEngine.Health health = new MaintenanceRuleEngine()
                .evaluate(48L * 3600L, 2, 10, 50L * 3600L, 100, 90, 60, 80);

        assertEquals("MAINTENANCE_DUE_SOON", health.status());
        assertFalse(health.blocked());
        assertEquals(2L * 3600L, health.remainingSeconds());
        assertTrue(health.exceedsBudget(4L * 3600L, 0, 0));
        assertFalse(health.exceedsBudget(2L * 3600L, 0, 0));
    }

    @Test
    void invalidThresholdsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MaintenanceRuleEngine().evaluate(0L, 0, 0, 3600L, 10, 30, 90, 80));
    }

    @Test
    void staff38HoursWith4HourMissionBlockedUnder40HourWeeklyPolicy() {
        WorkforceRuleEngine.PolicyLimits policy = new WorkforceRuleEngine.PolicyLimits(
                8L * 3600L,
                40L * 3600L,
                160L * 3600L,
                8L * 3600L,
                80,
                Set.of("DRONE_PILOT_A2"));

        WorkforceRuleEngine.Evaluation result = new WorkforceRuleEngine().evaluate(
                4L * 3600L,
                42L * 3600L,
                60L * 3600L,
                12L * 3600L,
                Set.of("DRONE_PILOT_A2"),
                policy);

        assertTrue(result.blocked());
        assertEquals("BLOCKED", result.status());
        assertTrue(result.blockingReasons().contains("WEEKLY_WORKLOAD_LIMIT_EXCEEDED"));
    }

    @Test
    void insufficientRestAndMissingCertificateAreBlocked() {
        WorkforceRuleEngine.PolicyLimits policy = new WorkforceRuleEngine.PolicyLimits(
                8L * 3600L,
                40L * 3600L,
                null,
                10L * 3600L,
                80,
                Set.of("VLOS_CERT"));

        WorkforceRuleEngine.Evaluation result = new WorkforceRuleEngine().evaluate(
                4L * 3600L,
                12L * 3600L,
                12L * 3600L,
                2L * 3600L,
                Set.of(),
                policy);

        assertTrue(result.blocked());
        assertTrue(result.blockingReasons().contains("INSUFFICIENT_REST_TIME"));
        assertTrue(result.blockingReasons().contains("MISSING_CERTIFICATE:VLOS_CERT"));
    }

    @Test
    void weightedScoringRanksEligibleBeforeBlockedAndSurfacesMissingData() {
        AllocationScoringEngine engine = new AllocationScoringEngine();
        List<AllocationScoringEngine.ScoredCandidate> ranked = engine.scoreAndRank(
                List.of(
                        new AllocationScoringEngine.CandidateInput(
                                "dev-blocked", "DEVICE", "Drone Blocked", false,
                                List.of("MAINTENANCE_REQUIRED"), List.of(),
                                0.0, 0.0, 1.0, 1.0),
                        new AllocationScoringEngine.CandidateInput(
                                "dev-missing", "DEVICE", "Drone Missing Data", true,
                                List.of(), List.of(),
                                null, null, null, null),
                        new AllocationScoringEngine.CandidateInput(
                                "dev-healthy", "DEVICE", "Drone Healthy", true,
                                List.of(), List.of(),
                                0.90, 0.20, 1.0, 1.0)),
                AllocationScoringEngine.Weights.defaults());

        assertEquals("dev-healthy", ranked.get(0).resourceId());
        assertNotNull(ranked.get(0).totalScore());
        assertEquals("dev-missing", ranked.get(1).resourceId());
        assertNull(ranked.get(1).totalScore());
        assertTrue(ranked.get(1).missingDataFlags().contains("HEALTH_MARGIN_UNKNOWN"));
        assertEquals("dev-blocked", ranked.get(2).resourceId());
        assertFalse(ranked.get(2).eligible());
        assertNull(ranked.get(2).totalScore());
    }
}
