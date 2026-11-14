package com.ondemandmonitoring.finance.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.finance.dto.MissionFinancialSummaryResponse;
import com.ondemandmonitoring.finance.service.impl.MissionFinancialSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/missions")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
@RequiredArgsConstructor
public class ManagerMissionFinancialSummaryController {
    private final MissionFinancialSummaryService financialSummary;

    @GetMapping("/{missionId}/financial-summary")
    public ResponseEntity<ApiResponse<MissionFinancialSummaryResponse>> getMissionFinancialSummary(
            @PathVariable String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Mission financial summary loaded", financialSummary.getForManager(missionId)));
    }
}
