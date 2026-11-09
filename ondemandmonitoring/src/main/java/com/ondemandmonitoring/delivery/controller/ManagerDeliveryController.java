package com.ondemandmonitoring.delivery.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.delivery.dto.request.ReleasePreviewRequest;
import com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest;
import com.ondemandmonitoring.delivery.dto.request.SaveChecklistDeliveryDraftRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import com.ondemandmonitoring.delivery.dto.response.ManagerDeliveryChecklistsResponse;
import com.ondemandmonitoring.delivery.dto.response.ChecklistDeliveryDraftResponse;
import com.ondemandmonitoring.delivery.service.IChecklistDeliveryService;
import com.ondemandmonitoring.delivery.service.IChecklistDeliveryDraftService;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Manager endpoints for preview publication and original-media release. */
@RestController
@RequestMapping("/api/manager/orders/{orderId}/delivery")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
@RequiredArgsConstructor
public class ManagerDeliveryController {
    private final IDeliveryWorkflowService delivery;
    private final IChecklistDeliveryService checklistDelivery;
    private final IChecklistDeliveryDraftService deliveryDraft;

    @GetMapping
    public ApiResponse<DeliveryResponse> get(@PathVariable String orderId) {
        return ApiResponse.ok(delivery.get(orderId));
    }

    @GetMapping("/checklists")
    public ApiResponse<ManagerDeliveryChecklistsResponse> getChecklists(@PathVariable String orderId) {
        return ApiResponse.ok(checklistDelivery.get(orderId));
    }

    @GetMapping("/draft")
    public ApiResponse<ChecklistDeliveryDraftResponse> getDraft(@PathVariable String orderId) {
        return ApiResponse.ok(deliveryDraft.get(orderId));
    }

    @PutMapping("/draft")
    public ApiResponse<ChecklistDeliveryDraftResponse> saveDraft(@PathVariable String orderId,
            @Valid @RequestBody SaveChecklistDeliveryDraftRequest request) {
        return ApiResponse.ok(deliveryDraft.save(orderId, request));
    }

    @DeleteMapping("/draft")
    public ApiResponse<Void> clearDraft(@PathVariable String orderId,
            @RequestParam long expectedDraftVersion) {
        deliveryDraft.clear(orderId, expectedDraftVersion);
        return ApiResponse.ok(null);
    }

    @PostMapping("/checklists/publish")
    public ApiResponse<DeliveryResponse> publishChecklists(@PathVariable String orderId,
            @Valid @RequestBody ChecklistDeliveryPublishRequest request) {
        return ApiResponse.ok("Checklist results published", checklistDelivery.publish(orderId, request));
    }

    @PostMapping("/release-preview")
    public ApiResponse<DeliveryResponse> releasePreview(
            @PathVariable String orderId,
            @Valid @RequestBody ReleasePreviewRequest request) {
        return ApiResponse.ok("Preview released", delivery.releasePreview(orderId, request));
    }

    @PostMapping("/release-originals")
    public ApiResponse<DeliveryResponse> releaseOriginals(@PathVariable String orderId) {
        return ApiResponse.ok("Original deliverables released", delivery.releaseOriginals(orderId));
    }
}
