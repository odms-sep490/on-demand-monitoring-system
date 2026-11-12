package com.ondemandmonitoring.audit.dto;

import java.util.List;

/** Page shape consumed by the web Audit Log page: 1-based page with total count. */
public record AuditLogPageResponse(List<AuditEntryResponse> items, long total, int page, int limit) {
}
