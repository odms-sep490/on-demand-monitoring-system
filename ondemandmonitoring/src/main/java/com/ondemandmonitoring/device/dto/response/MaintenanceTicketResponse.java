package com.ondemandmonitoring.device.dto.response;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceTicketResponse {

    private String id;
    private String ticketCode;
    private String deviceId;
    private String deviceCode;
    private String assignedStaffId;
    private String assignedStaffName;
    private String reportedBy;
    private String issueType;
    private String severity;
    private String description;
    private String status;
    private String resolutionNotes;
    private Instant openedAt;
    private Instant resolvedAt;
    private Instant closedAt;
    /** Effective workflow stage (e.g. DRAFT_HANDOVER, AWAITING_TECHNICAL_RECEIPT, IN_MAINTENANCE). */
    private String workflowStage;
}

