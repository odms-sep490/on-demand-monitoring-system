package com.ondemandmonitoring.checklist.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.checklist.enums.ChecklistConfigAuditAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable record of one administrator change to the checklist library or a service template.
 * Names are copied in, so the history stays readable after a checklist is renamed or a user is removed.
 */
@Entity
@Table(name = "checklist_config_audits", indexes = {
        @Index(name = "ix_checklist_config_audit_checklist", columnList = "checklist_id,created_at"),
        @Index(name = "ix_checklist_config_audit_service", columnList = "service_id,created_at")})
@Getter
@Setter
@NoArgsConstructor
public class ChecklistConfigAudit extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40, updatable = false)
    private ChecklistConfigAuditAction action;
    @Column(name = "checklist_id", length = 36, updatable = false)
    private String checklistId;
    @Column(name = "checklist_content", length = 500, updatable = false)
    private String checklistContent;
    @Column(name = "service_id", length = 36, updatable = false)
    private String serviceId;
    @Column(name = "service_name", length = 255, updatable = false)
    private String serviceName;
    @Column(name = "actor_id", length = 36, updatable = false)
    private String actorId;
    @Column(name = "actor_name", length = 255, updatable = false)
    private String actorName;
    /** JSON array of {field, from, to}; null when the action has no field-level change. */
    @Column(name = "changes", columnDefinition = "text", updatable = false)
    private String changes;
}
