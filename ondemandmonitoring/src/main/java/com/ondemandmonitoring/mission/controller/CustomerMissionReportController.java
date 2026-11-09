package com.ondemandmonitoring.mission.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.response.*;
import com.ondemandmonitoring.mission.service.ICustomerMissionReportQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/orders/{orderId}/missions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerMissionReportController {
    private final ICustomerMissionReportQueryService service;

    @GetMapping
    public ApiResponse<List<CustomerMissionSummary>> list(@PathVariable String orderId) {
        return ApiResponse.ok(service.list(orderId));
    }

    @GetMapping("/{missionId}/report")
    public ApiResponse<CustomerMissionReport> report(@PathVariable String orderId, @PathVariable String missionId) {
        return ApiResponse.ok(service.report(orderId, missionId));
    }
}
