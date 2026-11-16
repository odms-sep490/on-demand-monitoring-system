package com.ondemandmonitoring.recommendation.dto;

import com.ondemandmonitoring.recommendation.domain.RecommendationRisk;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record MissionRecommendationsResponse(
        String missionId,
        SearchWindow searchWindow,
        FixedTimeWindow fixedTimeWindow,
        AiEstimation aiEstimation,
        List<RecommendationOption> recommendations) {

    public record SearchWindow(LocalDate from, LocalDate to) {}
    public record FixedTimeWindow(LocalTime start, LocalTime end, String timezone) {}

    public record AiEstimation(
            int minimumSuggestedDevices,
            int recommendedDevices,
            String reason,
            List<String> assumptions,
            List<String> warnings,
            String model,
            Instant generatedAt) {}

    public record RecommendationOption(
            String id,
            LocalDate date,
            int availableDeviceCount,
            int capabilityCompatibleDeviceCount,
            int availableStaffCount,
            int recommendedDeviceCount,
            int deviceShortageCount,
            int staffShortageCount,
            BigDecimal deviceAvailabilityRatio,
            BigDecimal staffAvailabilityRatio,
            BigDecimal effectiveAvailabilityRatio,
            RecommendationRisk riskLevel,
            List<String> requiredCapabilities,
            List<DeviceCandidate> deviceCandidates,
            List<String> warnings) {}

    public record DeviceCandidate(String id, String code, String name, List<String> capabilityCodes) {}
}
