package com.ondemandmonitoring.finance.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.finance.dto.ConfigureMissionPaymentAllocationsRequest;
import com.ondemandmonitoring.finance.dto.MissionPaymentAllocationResponse;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentMilestoneService;
import com.ondemandmonitoring.finance.service.IMissionExecutionActivationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MissionPaymentMilestoneController {
    private final MissionPaymentMilestoneService milestones;
    private final IMissionExecutionActivationService activation;

    @GetMapping("/manager/orders/{orderId}/mission-payment-milestones")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<MissionPaymentAllocationResponse>> getForManager(
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Mission payment allocations loaded", milestones.getForManager(orderId)));
    }

    @PutMapping("/manager/orders/{orderId}/mission-payment-milestones")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<MissionPaymentAllocationResponse>> configure(
            @PathVariable String orderId,
            @Valid @RequestBody ConfigureMissionPaymentAllocationsRequest request) {
        var response = milestones.configure(orderId, request);
        activation.activateIfEligible(orderId);
        return ResponseEntity.ok(ApiResponse.ok("Mission payment allocations saved", response));
    }

    @GetMapping("/orders/{orderId}/mission-payment-milestones")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<MissionPaymentAllocationResponse>> getForCustomer(
            @PathVariable String orderId) {
        return ResponseEntity.ok(ApiResponse.ok("Mission payment milestones loaded", milestones.getForCustomer(orderId)));
    }
}
