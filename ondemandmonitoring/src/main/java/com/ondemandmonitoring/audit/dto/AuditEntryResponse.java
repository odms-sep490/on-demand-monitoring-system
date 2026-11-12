package com.ondemandmonitoring.audit.dto;

import com.ondemandmonitoring.audit.enums.AuditAction;
import java.time.Instant;
import java.util.Map;

public record AuditEntryResponse(
        String id,
        Instant createdAt,
        String actorId,
        String actorName,
        AuditAction action,
        String entityType,
        String entityId,
        String ip,
        String userAgent,
        Map<String, Object> before,
        Map<String, Object> after) {
}
