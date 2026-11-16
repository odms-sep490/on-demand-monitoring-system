package com.ondemandmonitoring.recommendation.api;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.recommendation.application.MissionRecommendationService;
import com.ondemandmonitoring.recommendation.dto.MissionRecommendationsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Recommendation API is a separate feature boundary; Mission ID only scopes the request. */
@RestController
@RequestMapping("/api/missions/{missionId}/recommendations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class MissionRecommendationController {
    private final MissionRecommendationService recommendations;

    @PostMapping
    public ApiResponse<MissionRecommendationsResponse> generate(@PathVariable String missionId) {
        return ApiResponse.ok(recommendations.generate(missionId));
    }
}
