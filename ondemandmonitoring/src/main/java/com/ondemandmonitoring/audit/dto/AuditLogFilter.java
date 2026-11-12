package com.ondemandmonitoring.audit.dto;

import com.ondemandmonitoring.audit.enums.AuditAction;
import java.time.LocalDate;

public record AuditLogFilter(
        String actorId,
        String actorQuery,
        AuditAction action,
        String entityType,
        LocalDate from,
        LocalDate to) {
    public AuditLogFilter(String actorId, AuditAction action, String entityType, LocalDate from, LocalDate to) {
        this(actorId, null, action, entityType, from, to);
    }
}
