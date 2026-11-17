package com.ondemandmonitoring.recommendation.application.port;

import java.time.Instant;
import java.util.List;

/** Normalized AI demand estimate consumed by the recommendation use case. */
public interface AiEstimatePort {
    Estimate estimate(String missionId);

    record Estimate(
            int minimumSuggestedDevices,
            int recommendedDevices,
            String reason,
            List<String> assumptions,
            List<String> warnings,
            String model,
            Instant generatedAt) {
    }
}
