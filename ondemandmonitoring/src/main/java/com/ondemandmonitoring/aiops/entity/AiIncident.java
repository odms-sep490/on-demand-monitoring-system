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
@Table(name = "ai_incidents")
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiIncident extends BaseEntity {

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "severity", length = 24, nullable = false)
    private String severity = "WARNING";

    @Column(name = "status", length = 24, nullable = false)
    private String status = "OPEN";

    @Column(name = "model_code", length = 80)
    private String modelCode;

    @Column(name = "service_endpoint", length = 255)
    private String serviceEndpoint;

    @Column(name = "summary", columnDefinition = "text", nullable = false)
    private String summary;

    @Column(name = "recommended_action", columnDefinition = "text")
    private String recommendedAction;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
