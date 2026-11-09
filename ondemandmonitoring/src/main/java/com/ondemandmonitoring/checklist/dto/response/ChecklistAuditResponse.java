package com.ondemandmonitoring.checklist.dto.response;

import com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction;
import java.time.Instant;
import java.util.List;

public record ChecklistAuditResponse(
        String id,
        ChecklistConfigAuditAction action,
        String checklistId,
        String checklistContent,
        String serviceId,
        String serviceName,
        String actorId,
        String actorName,
        List<Change> changes,
        Instant createdAt) {

    /** One field that changed; {@code from} is null for a new value, {@code to} is null for a cleared one. */
    public record Change(String field, String from, String to) {}
}
