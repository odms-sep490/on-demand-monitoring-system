package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.maintenance.enums.PostCheckResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_post_checks")
@Getter
@Setter
@NoArgsConstructor
public class MaintenancePostCheck extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_version_id", nullable = false)
    private MaintenanceReportVersion reportVersion;

    @Column(name = "check_name", nullable = false, length = 255)
    private String checkName;

    @Column(name = "check_method", nullable = false, length = 500)
    private String checkMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 30)
    private PostCheckResult result;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "not_applicable_reason", length = 1000)
    private String notApplicableReason;

    @Column(name = "required", nullable = false)
    private boolean required = true;

    @Column(name = "evidence_required", nullable = false)
    private boolean evidenceRequired = false;

    @Column(name = "evidence_refs_json", columnDefinition = "text")
    private String evidenceRefsJson;
}
