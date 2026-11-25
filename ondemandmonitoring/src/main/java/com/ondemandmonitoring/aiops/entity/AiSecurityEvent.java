package com.ondemandmonitoring.aiops.entity;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ai_security_events")
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiSecurityEvent extends BaseEntity {

    @Column(name = "trace_id", length = 80)
    private String traceId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "risk_category", length = 64, nullable = false)
    private String riskCategory;

    @Column(name = "severity", length = 24, nullable = false)
    private String severity = "MEDIUM";

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "feature", length = 120, nullable = false)
    private String feature;

    @Column(name = "decision", length = 24, nullable = false)
    private String decision = "BLOCK";

    @Column(name = "summary", columnDefinition = "text", nullable = false)
    private String summary;
}
