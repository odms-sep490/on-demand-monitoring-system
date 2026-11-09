package com.ondemandmonitoring.pricing.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests;
import com.ondemandmonitoring.pricing.dto.ServicePricePreviewResponse;
import com.ondemandmonitoring.pricing.service.ChecklistPricingPreviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ChecklistPricingController {
    private final ChecklistPricingPreviewService preview;

    @GetMapping("/api/admin/pricing/services/{serviceId}/default-price")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ServicePricePreviewResponse> defaultPrice(@PathVariable String serviceId) {
        return ApiResponse.ok(preview.defaultPrice(serviceId));
    }

    @PostMapping("/api/admin/pricing/service-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ServicePricePreviewResponse> adminPreview(@Valid @RequestBody ChecklistPricingPreviewRequests.Admin request) {
        return ApiResponse.ok(preview.adminPreview(request));
    }

    @PostMapping("/api/pricing/checklist-quote")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<ServicePricePreviewResponse> customerQuote(@Valid @RequestBody ChecklistPricingPreviewRequests.Customer request) {
        return ApiResponse.ok(preview.customerQuote(request));
    }
}
