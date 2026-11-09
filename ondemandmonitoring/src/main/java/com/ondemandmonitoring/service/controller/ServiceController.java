package com.ondemandmonitoring.service.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.pricing.service.ServicePriceResolver;
import com.ondemandmonitoring.service.dto.request.ServiceRequest;
import com.ondemandmonitoring.service.dto.response.ServicePricingEstimateResponse;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.service.IServiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Services", description = "APIs for managing monitoring services")
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ServiceController {

    IServiceService serviceService;
    ServicePriceResolver priceResolver;

    @Operation(summary = "Create service", description = "Creates a new monitoring service")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServiceResponse>> create(
            @Valid @RequestBody ServiceRequest request) {
        ServiceResponse response = serviceService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created("Service created successfully", response));
    }

    @Operation(summary = "Get service by ID", description = "Retrieves a service by its ID")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> getById(@PathVariable String id) {
        ServiceResponse response = withDefaultPrice(serviceService.getById(id));
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Get all services", description = "Retrieves all services. Use activeOnly=true to filter active services only.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ServiceResponse>>> getAll(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {
        List<ServiceResponse> response = activeOnly
                ? serviceService.getAllActive()
                : serviceService.getAll();
        response.forEach(this::withDefaultPrice);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "Estimate service pricing", description = "Returns backend-owned pricing for the selected service and optional add-ons.")
    @GetMapping("/pricing-estimate")
    public ResponseEntity<ApiResponse<ServicePricingEstimateResponse>> estimatePricing(
            @RequestParam String serviceId) {

        Service service = serviceService.getEntityById(serviceId);
        if (!Boolean.TRUE.equals(service.getIsActive())) {
            throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        }

        List<ServicePricingEstimateResponse.AdditionalRequirementPrice> additionalRequirements =
                new ArrayList<>();

        BigDecimal additionalTotal = additionalRequirements.stream()
                .map(ServicePricingEstimateResponse.AdditionalRequirementPrice::additionalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Same figure as the checklist quote's default total: the sum of the default checklists, or the legacy catalog
        // price while none of them is priced. The response shape is unchanged.
        BigDecimal servicePrice = priceResolver.forDisplay(service).total();
        ServicePricingEstimateResponse response = ServicePricingEstimateResponse.builder()
                .serviceId(serviceId)
                .servicePrice(servicePrice)
                .additionalRequirements(additionalRequirements)
                .totalPrice(servicePrice.add(additionalTotal))
                .build();

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    private ServiceResponse withDefaultPrice(ServiceResponse response) {
        var resolved = priceResolver.forDisplay(serviceService.getEntityById(response.getId()));
        response.setDefaultPrice(resolved.total());
        response.setPriceSource(resolved.source());
        return response;
    }

    @Operation(summary = "Update service", description = "Updates an existing service by its ID")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ServiceResponse>> update(
            @PathVariable String id,
            @Valid @RequestBody ServiceRequest request) {
        ServiceResponse response = serviceService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Service updated successfully", response));
    }

    @Operation(summary = "Delete service", description = "Soft-deletes a service by setting isActive to false")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        serviceService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Service deleted successfully", null));
    }

}
