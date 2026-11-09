package com.ondemandmonitoring.checklist.controller;

import com.ondemandmonitoring.checklist.dto.response.ChecklistAuditResponse;
import com.ondemandmonitoring.checklist.service.ChecklistAuditService;
import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only change history of the checklist library and of service checklist templates. */
@RestController
@RequestMapping("/api/admin/checklist-audits")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ChecklistAuditController {
    private final ChecklistAuditService audits;

    @GetMapping
    public ApiResponse<PageResponse<ChecklistAuditResponse>> list(
            @RequestParam(required = false) String checklistId,
            @RequestParam(required = false) String serviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(audits.list(checklistId, serviceId, page, size));
    }
}
