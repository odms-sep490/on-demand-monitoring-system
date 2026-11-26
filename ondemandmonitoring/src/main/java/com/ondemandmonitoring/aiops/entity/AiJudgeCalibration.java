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
@Table(name = "ai_judge_calibrations")
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiJudgeCalibration extends BaseEntity {

    @Column(name = "judge_model", length = 120, nullable = false)
    private String judgeModel;

    @Column(name = "rubric_version", length = 64, nullable = false)
    private String rubricVersion;

    @Column(name = "dataset_version", length = 64, nullable = false)
    private String datasetVersion;

    @Column(name = "total_cases", nullable = false)
    private int totalCases;

    @Column(name = "agreement_rate", nullable = false)
    private double agreementRate;

    @Column(name = "false_positives", nullable = false)
    private int falsePositives = 0;

    @Column(name = "false_negatives", nullable = false)
    private int falseNegatives = 0;

    @Column(name = "abstention_rate", nullable = false)
    private double abstentionRate = 0.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cases_json", nullable = false, columnDefinition = "jsonb")
    private String casesJson = "[]";
}
