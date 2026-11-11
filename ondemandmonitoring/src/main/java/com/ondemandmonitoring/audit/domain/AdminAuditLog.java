package com.ondemandmonitoring.audit.domain;

import com.ondemandmonitoring.audit.enums.AuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable, cross-domain record of an administrative change. */
@Entity
@Table(name = "admin_audit_logs", indexes = {
        @Index(name = "ix_admin_audit_logs_created_at", columnList = "created_at"),
        @Index(name = "ix_admin_audit_logs_actor_id", columnList = "actor_id"),
        @Index(name = "ix_admin_audit_logs_entity_type", columnList = "entity_type")
})
@Getter
@Setter
@NoArgsConstructor
public class AdminAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "actor_id", nullable = false, length = 255, updatable = false)
    private String actorId;

    @Column(name = "actor_name", nullable = false, length = 255, updatable = false)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30, updatable = false)
    private AuditAction action;

    @Column(name = "entity_type", nullable = false, length = 80, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 255, updatable = false)
    private String entityId;

    @Column(name = "ip", length = 64, updatable = false)
    private String ip;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> after;
}
