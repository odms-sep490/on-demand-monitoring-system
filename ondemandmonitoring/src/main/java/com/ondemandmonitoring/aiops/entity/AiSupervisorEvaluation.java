package com.ondemandmonitoring.aiops.entity;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_supervisor_evaluations")
@AttributeOverrides({
        @AttributeOverride(name = "id", column = @Column(name = "evaluation_id", length = 64, nullable = false, updatable = false)),
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiSupervisorEvaluation extends BaseEntity {

    @Column(name = "trace_id", length = 120, nullable = false)
    private String traceId;

    @Column(name = "target_model", length = 120, nullable = false)
    private String targetModel;

    @Column(name = "judge_model", length = 120, nullable = false)
    private String judgeModel;

    @Column(name = "rubric_version", length = 64, nullable = false)
    private String rubricVersion;

    @Column(name = "feature", length = 80, nullable = false)
    private String feature;

    @Column(name = "evaluation_status", length = 40, nullable = false)
    private String evaluationStatus;

    @Column(name = "quality_score")
    private Double qualityScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metric_scores_json", nullable = false, columnDefinition = "jsonb")
    private String metricScoresJson = "{}";

    @Column(name = "task_completed")
    private Boolean taskCompleted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hard_constraint_violations_json", nullable = false, columnDefinition = "jsonb")
    private String hardConstraintViolationsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detected_errors_json", nullable = false, columnDefinition = "jsonb")
    private String detectedErrorsJson = "[]";

    @Column(name = "severity", length = 24, nullable = false)
    private String severity = "NONE";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_references_json", nullable = false, columnDefinition = "jsonb")
    private String evidenceReferencesJson = "[]";

    @Column(name = "suspected_root_cause", length = 160, nullable = false)
    private String suspectedRootCause = "NONE";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "improvement_suggestions_json", nullable = false, columnDefinition = "jsonb")
    private String improvementSuggestionsJson = "[]";

    @Column(name = "human_review_required", nullable = false)
    private boolean humanReviewRequired = false;

    @Column(name = "evaluation_latency_ms", nullable = false)
    private int evaluationLatencyMs = 0;

    @Column(name = "eval_input_tokens")
    private Integer evalInputTokens;

    @Column(name = "eval_output_tokens")
    private Integer evalOutputTokens;
}
