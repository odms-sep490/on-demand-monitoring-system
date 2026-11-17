package com.ondemandmonitoring.pricing.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.pricing.dto.BaseChecklistPriceConfig;
import com.ondemandmonitoring.pricing.service.BaseChecklistPricingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/pricing/base-checklists")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BaseChecklistPricingController {
    private final BaseChecklistPricingService pricing;

    @GetMapping
    public ApiResponse<List<BaseChecklistPriceConfig.Row>> list() {
        return ApiResponse.ok(pricing.list());
    }

    @PutMapping("/{checklistId}")
    public ApiResponse<BaseChecklistPriceConfig.Row> update(@PathVariable String checklistId,
            @Valid @RequestBody BaseChecklistPriceConfig.Update request) {
        return ApiResponse.ok("Base Checklist price saved", pricing.update(checklistId, request));
    }

    @PostMapping("/preview")
    public ApiResponse<List<BaseChecklistPriceConfig.Row>> preview(@Valid @RequestBody BaseChecklistPriceConfig.Preview request) {
        return ApiResponse.ok(pricing.preview(request.actualAreaM2()));
    }
}
