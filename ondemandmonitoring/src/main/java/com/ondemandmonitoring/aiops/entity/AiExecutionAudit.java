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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_execution_audits")
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiExecutionAudit extends BaseEntity {

    @Column(name = "trace_id", length = 80, nullable = false)
    private String traceId;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "user_role", length = 64)
    private String userRole;

    @Column(name = "model_code", length = 80, nullable = false)
    private String modelCode;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "prompt_version", length = 48)
    private String promptVersion;

    @Column(name = "feature", length = 120, nullable = false)
    private String feature;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "input_ref", length = 255)
    private String inputRef;

    @Column(name = "output_ref", length = 255)
    private String outputRef;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs = 0;

    @Column(name = "http_status", nullable = false)
    private int httpStatus = 200;

    @Column(name = "inference_device", length = 32, nullable = false)
    private String inferenceDevice = "CPU";

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed = false;

    @Column(name = "validation_status", length = 32, nullable = false)
    private String validationStatus = "PASSED";

    @Column(name = "security_decision", length = 32, nullable = false)
    private String securityDecision = "ALLOW";

    @Column(name = "human_review_status", length = 32, nullable = false)
    private String humanReviewStatus = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spans_json", nullable = false, columnDefinition = "jsonb")
    private String spansJson = "[]";
}
