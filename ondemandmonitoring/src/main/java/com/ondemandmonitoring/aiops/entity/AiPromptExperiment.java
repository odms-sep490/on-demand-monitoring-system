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
@Table(name = "ai_prompt_experiments")
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiPromptExperiment extends BaseEntity {

    @Column(name = "prompt_key", length = 80, nullable = false)
    private String promptKey;

    @Column(name = "baseline_version", length = 40, nullable = false)
    private String baselineVersion;

    @Column(name = "candidate_version", length = 40, nullable = false)
    private String candidateVersion;

    @Column(name = "baseline_quality", nullable = false)
    private double baselineQuality;

    @Column(name = "candidate_quality", nullable = false)
    private double candidateQuality;

    @Column(name = "baseline_tokens", nullable = false)
    private double baselineTokens;

    @Column(name = "candidate_tokens", nullable = false)
    private double candidateTokens;

    @Column(name = "baseline_latency_ms", nullable = false)
    private double baselineLatencyMs;

    @Column(name = "candidate_latency_ms", nullable = false)
    private double candidateLatencyMs;

    @Column(name = "baseline_error_rate", nullable = false)
    private double baselineErrorRate;

    @Column(name = "candidate_error_rate", nullable = false)
    private double candidateErrorRate;

    @Column(name = "hard_constraint_violations", nullable = false)
    private int hardConstraintViolations = 0;

    @Column(name = "pareto_eligible", nullable = false)
    private boolean paretoEligible = false;

    @Column(name = "status", length = 32, nullable = false)
    private String status = "PENDING_APPROVAL";

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;
}
