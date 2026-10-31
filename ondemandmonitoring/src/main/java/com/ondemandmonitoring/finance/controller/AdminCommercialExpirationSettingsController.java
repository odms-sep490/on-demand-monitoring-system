package com.ondemandmonitoring.finance.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsAuditResponse;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsRequest;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsResponse;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationSettingsService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/config/order-expiration")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCommercialExpirationSettingsController {
    private final CommercialExpirationSettingsService settings;
    private final AuthenticatedUserResolver currentUser;

    @GetMapping
    public ApiResponse<CommercialExpirationSettingsResponse> current() {
        return ApiResponse.ok(settings.current());
    }

    @PutMapping
    public ApiResponse<CommercialExpirationSettingsResponse> update(
            @Valid @RequestBody CommercialExpirationSettingsRequest request) {
        String actorName = currentUser.getCurrentUser().getFullName();
        return ApiResponse.ok("Expiration settings updated", settings.update(request, actorName));
    }

    @GetMapping("/audit")
    public ApiResponse<List<CommercialExpirationSettingsAuditResponse>> audit(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(settings.recentAudit(limit));
    }
}
