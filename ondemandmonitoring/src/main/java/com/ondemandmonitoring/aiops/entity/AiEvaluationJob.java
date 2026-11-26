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
@Table(name = "ai_evaluation_jobs")
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiEvaluationJob extends BaseEntity {

    @Column(name = "status", length = 24, nullable = false)
    private String status = "QUEUED";

    @Column(name = "mode", length = 48, nullable = false)
    private String mode = "ALL";

    @Column(name = "metric_filter", length = 120)
    private String metricFilter;

    @Column(name = "partition", length = 32, nullable = false)
    private String partition = "all";

    @Column(name = "total_cases", nullable = false)
    private int totalCases = 0;

    @Column(name = "completed_cases", nullable = false)
    private int completedCases = 0;

    @Column(name = "passed_cases", nullable = false)
    private int passedCases = 0;

    @Column(name = "failed_cases", nullable = false)
    private int failedCases = 0;

    @Column(name = "error_cases", nullable = false)
    private int errorCases = 0;

    @Column(name = "current_metric", length = 80)
    private String currentMetric;

    @Column(name = "current_case", length = 80)
    private String currentCase;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs = 0L;

    @Column(name = "last_checkpoint", length = 255)
    private String lastCheckpoint;

    @Column(name = "created_by", length = 255)
    private String createdBy;
}
