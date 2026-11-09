package com.ondemandmonitoring.pricing.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.pricing.dto.*;
import com.ondemandmonitoring.pricing.service.PricingPolicyService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/pricing-policies") @PreAuthorize("hasRole('ADMIN')") @RequiredArgsConstructor
public class AdminPricingPolicyController {
    private final PricingPolicyService service;
    @GetMapping public ApiResponse<List<PricingPolicyResponse>> list() { return ApiResponse.ok(service.list()); }
    @PostMapping public ApiResponse<PricingPolicyResponse> create(@Valid @RequestBody PricingPolicyRequest request) { return ApiResponse.ok("Pricing policy draft created", service.createDraft(request)); }
    @PostMapping("/{id}/clone") public ApiResponse<PricingPolicyResponse> clone(@PathVariable String id) { return ApiResponse.ok("Pricing policy draft cloned", service.cloneAsDraft(id)); }
    @PutMapping("/{id}") public ApiResponse<PricingPolicyResponse> update(@PathVariable String id, @Valid @RequestBody PricingPolicyRequest request) { return ApiResponse.ok("Pricing policy draft updated", service.updateDraft(id, request)); }
    @PostMapping("/{id}/publish") public ApiResponse<PricingPolicyResponse> publish(@PathVariable String id) { return ApiResponse.ok("Pricing policy published", service.publish(id)); }
}
