package com.ondemandmonitoring.device.maintenance.service;

import com.ondemandmonitoring.audit.enums.AuditAction;
import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceWorkflowEvent;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceWorkflowEventRepository;
import com.ondemandmonitoring.user.domain.User;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists immutable maintenance workflow timeline events and mirrors each action
 * into the existing AdminAuditLogService without duplicating audit infrastructure.
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class MaintenanceAuditAdapter {

    private final MaintenanceWorkflowEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AdminAuditLogService adminAuditLogService;

    @Transactional
    public MaintenanceWorkflowEvent record(
            MaintenanceTicket ticket,
            User actor,
            String action,
            AuditAction auditAction,
            MaintenanceWorkflowStage fromStage,
            MaintenanceWorkflowStage toStage,
            Integer revision,
            String reason,
            List<String> evidenceIds,
            Map<String, Object> metadata) {
        Instant now = Instant.now();
        MaintenanceWorkflowEvent event = new MaintenanceWorkflowEvent();
        event.setTicket(ticket);
        event.setDeviceId(ticket.getDevice().getId());
        event.setActor(actor);
        event.setAction(action);
        event.setFromStage(fromStage);
        event.setToStage(toStage);
        event.setRoundNumber(ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound());
        event.setRevision(revision);
        event.setReason(reason);
        event.setEvidenceRefsJson(toJson(evidenceIds));
        event.setMetadataJson(toJson(metadata));
        event.setOccurredAt(now);
        MaintenanceWorkflowEvent saved = eventRepository.save(event);

        if (adminAuditLogService != null) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("stage", fromStage == null ? null : fromStage.name());
            before.put("round", event.getRoundNumber());
            before.put("deviceId", ticket.getDevice().getId());

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("stage", toStage == null ? null : toStage.name());
            after.put("action", action);
            after.put("round", event.getRoundNumber());
            after.put("revision", revision);
            after.put("reason", reason);
            after.put("deviceId", ticket.getDevice().getId());

            adminAuditLogService.record(
                    auditAction,
                    "maintenance_ticket",
                    ticket.getId(),
                    before,
                    after);
        }
        return saved;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }
}
