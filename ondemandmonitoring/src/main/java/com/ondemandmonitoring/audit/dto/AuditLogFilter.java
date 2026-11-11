package com.ondemandmonitoring.audit.dto;

import com.ondemandmonitoring.audit.enums.AuditAction;
import java.time.LocalDate;

public record AuditLogFilter(String actorId, AuditAction action, String entityType, LocalDate from, LocalDate to) {
}
