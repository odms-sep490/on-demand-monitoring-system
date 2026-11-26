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
@Table(name = "ai_evaluation_runs")
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiEvaluationRun extends BaseEntity {

    @Column(name = "model_code", length = 80, nullable = false)
    private String modelCode;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "suite_name", length = 120, nullable = false)
    private String suiteName;

    @Column(name = "metric_name", length = 80, nullable = false)
    private String metricName;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "pass_rate", nullable = false)
    private double passRate = 0.0;

    @Column(name = "target_score", nullable = false)
    private double targetScore;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "total_cases", nullable = false)
    private int totalCases = 0;

    @Column(name = "passed_cases", nullable = false)
    private int passedCases = 0;

    @Column(name = "failed_cases", nullable = false)
    private int failedCases = 0;

    @Column(name = "skipped_cases", nullable = false)
    private int skippedCases = 0;

    @Column(name = "error_cases", nullable = false)
    private int errorCases = 0;

    @Column(name = "suite_group", length = 32, nullable = false)
    private String suiteGroup = "core";

    @Column(name = "dataset_version", length = 48, nullable = false)
    private String datasetVersion = "v1.0.0";

    @Column(name = "judge_model", length = 80, nullable = false)
    private String judgeModel = "qwen3:8b";

    @Column(name = "prompt_version", length = 80, nullable = false)
    private String promptVersion = "v1.0";

    @Column(name = "run_id", length = 80)
    private String runId;

    @Column(name = "baseline_delta")
    private Double baselineDelta;

    @Column(name = "avg_latency_ms", nullable = false)
    private int avgLatencyMs = 0;

    @Column(name = "p95_latency_ms", nullable = false)
    private int p95LatencyMs = 0;

    @Column(name = "live_scored", nullable = false)
    private int liveScored = 0;

    @Column(name = "live_passed", nullable = false)
    private int livePassed = 0;

    @Column(name = "source_artifact", length = 255)
    private String sourceArtifact;
}
