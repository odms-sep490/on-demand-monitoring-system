package com.ondemandmonitoring.recommendation.adapter.ai;

import com.ondemandmonitoring.ai.dto.AiResourceEstimateResponse;
import com.ondemandmonitoring.ai.service.AiResourceEstimateService;
import com.ondemandmonitoring.recommendation.application.port.AiEstimatePort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiResourceEstimateAdapter implements AiEstimatePort {
    private final AiResourceEstimateService estimates;

    @Override
    public Estimate estimate(String missionId) {
        AiResourceEstimateResponse result = estimates.estimate(missionId);
        return new Estimate(
                result.minimumSuggestedDevices(),
                result.recommendedDevices(),
                result.reason(),
                result.assumptions() == null ? List.of() : result.assumptions(),
                result.warnings() == null ? List.of() : result.warnings(),
                result.model(),
                result.generatedAt());
    }
}
