package com.ondemandmonitoring.audit.controller;

import com.ondemandmonitoring.audit.dto.AuditLogFilter;
import com.ondemandmonitoring.audit.dto.AuditLogPageResponse;
import com.ondemandmonitoring.audit.enums.AuditAction;
import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.common.api.ApiResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAuditLogController {
    private final AdminAuditLogService auditLog;

    @GetMapping
    public ApiResponse<AuditLogPageResponse> list(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String actorQuery,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(auditLog.search(
                new AuditLogFilter(actorId, actorQuery, action, entityType, from, to), page, limit));
    }
}
