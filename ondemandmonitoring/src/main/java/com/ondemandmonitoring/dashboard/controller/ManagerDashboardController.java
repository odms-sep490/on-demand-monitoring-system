package com.ondemandmonitoring.dashboard.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.dashboard.dto.ManagerDashboardResponse;
import com.ondemandmonitoring.dashboard.service.ManagerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/dashboard")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
@RequiredArgsConstructor
public class ManagerDashboardController {
    private final ManagerDashboardService dashboardService;

    @GetMapping
    public ApiResponse<ManagerDashboardResponse> dashboard(
            @RequestParam(defaultValue = "30D") String range,
            @RequestParam(required = false) java.time.LocalDate from,
            @RequestParam(required = false) java.time.LocalDate to) {
        return ApiResponse.ok(dashboardService.getDashboard(range, from, to));
    }
}
