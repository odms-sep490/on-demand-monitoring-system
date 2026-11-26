package com.ondemandmonitoring.device.maintenance.service;

import com.ondemandmonitoring.device.maintenance.domain.MaintenanceAcceptanceDecision;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceHandover;
import com.ondemandmonitoring.device.maintenance.enums.AcceptanceDecisionType;
import com.ondemandmonitoring.device.maintenance.enums.HandoverDirection;
import com.ondemandmonitoring.device.maintenance.enums.HandoverStatus;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceAcceptanceDecisionRepository;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceHandoverRepository;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import com.ondemandmonitoring.device.maintenance.policy.TechnicalCapabilityResolver;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import com.ondemandmonitoring.notification.dto.NotificationItemResponse;
import com.ondemandmonitoring.notification.dto.NotificationType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable maintenance workflow notifications reconstructed from immutable handover
 * and acceptance decision milestones, integrated directly into NotificationFeedService.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenanceNotificationService {

    private final MaintenanceHandoverRepository handovers;
    private final MaintenanceAcceptanceDecisionRepository decisions;
    private final MaintenanceTicketRepository tickets;
    private final TechnicalCapabilityResolver capabilityResolver;
    private final UserRepository users;

    /** Shared-queue tickets older than this are escalated to managers. */
    static final Duration UNCLAIMED_ESCALATION = Duration.ofHours(2);

    private List<MaintenanceTicket> sharedQueue() {
        return tickets.findByAssignedStaffIsNullAndWorkflowStageOrderByWorkflowUpdatedAtAsc(
                MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
    }

    private static Instant queuedAt(MaintenanceTicket ticket) {
        return ticket.getWorkflowUpdatedAt() != null ? ticket.getWorkflowUpdatedAt() : ticket.getOpenedAt();
    }

    public List<NotificationItemResponse> technicalItems(String staffId, int limit) {
        List<NotificationItemResponse> items = new ArrayList<>();
        PageRequest page = PageRequest.of(0, limit);

        for (MaintenanceHandover handover : handovers
                .findByToUser_IdAndDirectionAndStatusOrderBySubmittedAtDesc(
                        staffId, HandoverDirection.INBOUND, HandoverStatus.SUBMITTED, page)) {
            items.add(new NotificationItemResponse(
                    "maint-handover:" + handover.getId(),
                    NotificationType.MAINTENANCE_HANDOVER_SENT,
                    handover.getTicket().getId(),
                    handover.getDevice().getId(),
                    handover.getTicket().getTicketCode(),
                    handover.getSubmittedAt() != null ? handover.getSubmittedAt() : handover.getCreatedAt()));
        }

        for (MaintenanceAcceptanceDecision decision : decisions
                .findByTicket_AssignedStaff_IdOrderByDecidedAtDesc(staffId, page)) {
            NotificationType type = decision.getDecision() == AcceptanceDecisionType.ACCEPTED
                    ? NotificationType.MAINTENANCE_ACCEPTED
                    : NotificationType.MAINTENANCE_REWORK_REQUIRED;
            items.add(new NotificationItemResponse(
                    "maint-decision:" + decision.getId(),
                    type,
                    decision.getTicket().getId(),
                    decision.getTicket().getDevice().getId(),
                    decision.getTicket().getTicketCode(),
                    decision.getDecidedAt()));
        }

        boolean qualified = users.findById(staffId).map(capabilityResolver::isQualifiedTechnical).orElse(false);
        if (qualified) {
            sharedQueue().stream().limit(limit).forEach(ticket -> items.add(new NotificationItemResponse(
                    "maint-queue:" + ticket.getId(),
                    NotificationType.MAINTENANCE_QUEUE_AVAILABLE,
                    ticket.getId(),
                    ticket.getDevice() == null ? null : ticket.getDevice().getId(),
                    ticket.getTicketCode(),
                    queuedAt(ticket))));
        }
        return items;
    }

    public List<NotificationItemResponse> managerItems(String managerId, int limit) {
        List<NotificationItemResponse> items = new ArrayList<>();
        PageRequest page = PageRequest.of(0, limit);

        for (MaintenanceHandover handover : handovers
                .findByToUser_IdAndDirectionAndStatusOrderBySubmittedAtDesc(
                        managerId, HandoverDirection.RETURN, HandoverStatus.SUBMITTED, page)) {
            items.add(new NotificationItemResponse(
                    "maint-return:" + handover.getId(),
                    NotificationType.MAINTENANCE_RETURN_SUBMITTED,
                    handover.getTicket().getId(),
                    handover.getDevice().getId(),
                    handover.getTicket().getTicketCode(),
                    handover.getSubmittedAt() != null ? handover.getSubmittedAt() : handover.getCreatedAt()));
        }

        Instant cutoff = Instant.now().minus(UNCLAIMED_ESCALATION);
        sharedQueue().stream()
                .filter(ticket -> queuedAt(ticket) != null && queuedAt(ticket).isBefore(cutoff))
                .limit(limit)
                .forEach(ticket -> items.add(new NotificationItemResponse(
                        "maint-unclaimed:" + ticket.getId() + ":" + queuedAt(ticket),
                        NotificationType.MAINTENANCE_UNCLAIMED,
                        ticket.getId(),
                        ticket.getDevice() == null ? null : ticket.getDevice().getId(),
                        ticket.getTicketCode(),
                        queuedAt(ticket).plus(UNCLAIMED_ESCALATION))));
        return items;
    }
}
