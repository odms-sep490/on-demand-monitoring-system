package com.ondemandmonitoring.support.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class SupportTicketDto {
    private String id;
    private String ticketCode;
    private String customerId;
    private String customerName;
    private String orderId;
    private String missionId;
    private String category;
    private com.ondemandmonitoring.support.enums.SupportTicketKind ticketKind;
    private String subject;
    private String description;
    private String priority;
    private String status;
    private String assignedStaffId;
    private String assignedStaffName;
    private Instant openedAt;
    private Instant resolvedAt;
    private String resolutionNotes;
    private List<SupportMessageDto> messages;
}
