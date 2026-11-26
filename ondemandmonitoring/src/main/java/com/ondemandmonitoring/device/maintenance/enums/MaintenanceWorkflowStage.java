package com.ondemandmonitoring.device.maintenance.enums;

/**
 * Explicit maintenance workflow stages mapped cleanly onto MaintenanceTicket.
 * Ticket status (OPEN / IN_PROGRESS / RESOLVED / CLOSED) is always derived from this stage
 * to preserve backward compatibility with existing filters and dashboards.
 */
public enum MaintenanceWorkflowStage {
    DRAFT_HANDOVER("OPEN"),
    AWAITING_TECHNICAL_RECEIPT("OPEN"),
    RECEIPT_REJECTED("OPEN"),
    RECEIVED("IN_PROGRESS"),
    IN_MAINTENANCE("IN_PROGRESS"),
    WAITING_PARTS("IN_PROGRESS"),
    AWAITING_MANAGER_ACCEPTANCE("RESOLVED"),
    REWORK_REQUIRED("IN_PROGRESS"),
    COMPLETED("CLOSED"),
    CANCELLED("CLOSED");

    private final String ticketStatus;

    MaintenanceWorkflowStage(String ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    public String toTicketStatus() {
        return ticketStatus;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean isBlockingDevice() {
        return !isTerminal();
    }
}
