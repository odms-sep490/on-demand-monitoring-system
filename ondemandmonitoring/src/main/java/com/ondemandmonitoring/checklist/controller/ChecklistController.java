package com.ondemandmonitoring.checklist.controller;

import com.ondemandmonitoring.checklist.dto.request.*;
import com.ondemandmonitoring.checklist.dto.response.ChecklistResponse;
import com.ondemandmonitoring.checklist.service.IChecklistService;
import com.ondemandmonitoring.common.api.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/checklists")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ChecklistController {
    private final IChecklistService service;

    @PostMapping
    public ResponseEntity<ApiResponse<ChecklistResponse>> create(@Valid @RequestBody ChecklistRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.created("Checklist created", service.create(request)));
    }

    @GetMapping
    public ApiResponse<PageResponse<ChecklistResponse>> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(service.getAll(search, active, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ChecklistResponse> getById(@PathVariable String id) {
        return ApiResponse.ok(service.getById(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChecklistResponse> update(@PathVariable String id, @Valid @RequestBody ChecklistRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivate(@PathVariable String id) {
        service.updateStatus(id, false);
        return ApiResponse.ok("Checklist deactivated", null);
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<ChecklistResponse> status(@PathVariable String id, @Valid @RequestBody ChecklistStatusRequest request) {
        return ApiResponse.ok(service.updateStatus(id, request.getActive()));
    }
}
