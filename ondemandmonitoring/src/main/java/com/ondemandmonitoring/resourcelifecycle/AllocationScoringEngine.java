package com.ondemandmonitoring.resourcelifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure deterministic weighted scoring engine for resource allocation.
 * Hard constraints must always be evaluated prior to invoking this engine.
 * Missing data is explicitly surfaced rather than fabricated into fake scores.
 */
public final class AllocationScoringEngine {

    public static final String ALGORITHM_VERSION = "deterministic-weighted-v1";

    public record Weights(
            double healthMarginWeight,
            double workloadBalanceWeight,
            double restMarginWeight,
            double dataQualityWeight) {

        public static Weights defaults() {
            return new Weights(0.40, 0.30, 0.15, 0.15);
        }
    }

    public record CandidateInput(
            String resourceId,
            String resourceKind,
            String displayName,
            boolean eligible,
            List<String> blockingReasons,
            List<String> warningReasons,
            Double remainingBudgetRatio,
            Double workloadUtilizationRatio,
            Double restMarginRatio,
            Double dataCompletenessRatio) {
    }

    public record ScoredCandidate(
            String resourceId,
            String resourceKind,
            String displayName,
            boolean eligible,
            Double totalScore,
            Map<String, Double> scoreBreakdown,
            List<String> missingDataFlags,
            List<String> blockingReasons,
            List<String> warningReasons) {
    }

    public List<ScoredCandidate> scoreAndRank(List<CandidateInput> candidates, Weights weights) {
        Weights effectiveWeights = weights == null ? Weights.defaults() : weights;
        validateWeights(effectiveWeights);

        List<ScoredCandidate> results = new ArrayList<>();
        for (CandidateInput candidate : candidates) {
            results.add(scoreSingle(candidate, effectiveWeights));
        }

        results.sort(Comparator
                .comparing(ScoredCandidate::eligible).reversed()
                .thenComparing(
                        candidate -> candidate.totalScore() == null ? -1.0 : candidate.totalScore(),
                        Comparator.reverseOrder())
                .thenComparing(ScoredCandidate::resourceId));
        return List.copyOf(results);
    }

    private ScoredCandidate scoreSingle(CandidateInput candidate, Weights weights) {
        if (!candidate.eligible()) {
            return new ScoredCandidate(
                    candidate.resourceId(),
                    candidate.resourceKind(),
                    candidate.displayName(),
                    false,
                    null,
                    Map.of(),
                    List.of(),
                    candidate.blockingReasons() == null ? List.of() : List.copyOf(candidate.blockingReasons()),
                    candidate.warningReasons() == null ? List.of() : List.copyOf(candidate.warningReasons()));
        }

        Map<String, Double> breakdown = new LinkedHashMap<>();
        List<String> missingFlags = new ArrayList<>();
        double weightedSum = 0.0;
        double availableWeight = 0.0;

        if (candidate.remainingBudgetRatio() != null) {
            double clamped = clamp01(candidate.remainingBudgetRatio());
            breakdown.put("healthMargin", round2(clamped * 100.0));
            weightedSum += clamped * weights.healthMarginWeight();
            availableWeight += weights.healthMarginWeight();
        } else {
            missingFlags.add("HEALTH_MARGIN_UNKNOWN");
        }

        if (candidate.workloadUtilizationRatio() != null) {
            double balance = clamp01(1.0 - candidate.workloadUtilizationRatio());
            breakdown.put("workloadBalance", round2(balance * 100.0));
            weightedSum += balance * weights.workloadBalanceWeight();
            availableWeight += weights.workloadBalanceWeight();
        } else {
            missingFlags.add("WORKLOAD_UTILIZATION_UNKNOWN");
        }

        if (candidate.restMarginRatio() != null) {
            double clamped = clamp01(candidate.restMarginRatio());
            breakdown.put("restMargin", round2(clamped * 100.0));
            weightedSum += clamped * weights.restMarginWeight();
            availableWeight += weights.restMarginWeight();
        } else {
            missingFlags.add("REST_MARGIN_UNKNOWN");
        }

        if (candidate.dataCompletenessRatio() != null) {
            double clamped = clamp01(candidate.dataCompletenessRatio());
            breakdown.put("dataQuality", round2(clamped * 100.0));
            weightedSum += clamped * weights.dataQualityWeight();
            availableWeight += weights.dataQualityWeight();
        } else {
            missingFlags.add("DATA_QUALITY_UNKNOWN");
        }

        Double normalizedScore = availableWeight > 0.0
                ? round2((weightedSum / availableWeight) * 100.0)
                : null;

        return new ScoredCandidate(
                candidate.resourceId(),
                candidate.resourceKind(),
                candidate.displayName(),
                true,
                normalizedScore,
                Map.copyOf(breakdown),
                List.copyOf(missingFlags),
                List.of(),
                candidate.warningReasons() == null ? List.of() : List.copyOf(candidate.warningReasons()));
    }

    private void validateWeights(Weights weights) {
        if (weights.healthMarginWeight() < 0.0
                || weights.workloadBalanceWeight() < 0.0
                || weights.restMarginWeight() < 0.0
                || weights.dataQualityWeight() < 0.0) {
            throw new IllegalArgumentException("Allocation scoring weights cannot be negative");
        }
        double total = weights.healthMarginWeight()
                + weights.workloadBalanceWeight()
                + weights.restMarginWeight()
                + weights.dataQualityWeight();
        if (total <= 0.0) {
            throw new IllegalArgumentException("Allocation scoring weights sum must be positive");
        }
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
