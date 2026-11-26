package com.ondemandmonitoring.device.maintenance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceReportStatus;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "maintenance_report_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_maint_report_ticket_revision",
                columnNames = {"ticket_id", "revision"}))
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceReportVersion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @Column(name = "round_number", nullable = false)
    private Integer roundNumber = 1;

    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MaintenanceReportStatus status = MaintenanceReportStatus.DRAFT;

    @Column(name = "received_condition_summary", length = 2000)
    private String receivedConditionSummary;

    @Column(name = "fault_diagnosis_summary", length = 2000)
    private String faultDiagnosisSummary;

    @Column(name = "work_summary", length = 2000)
    private String workSummary;

    @Column(name = "parts_summary", length = 2000)
    private String partsSummary;

    @Column(name = "post_check_summary", length = 2000)
    private String postCheckSummary;

    @Column(name = "remaining_issues", length = 2000)
    private String remainingIssues;

    @Column(name = "usage_limitations", length = 2000)
    private String usageLimitations;

    @Column(name = "follow_up_recommendations", length = 2000)
    private String followUpRecommendations;

    @Column(name = "before_evidence_refs_json", columnDefinition = "text")
    private String beforeEvidenceRefsJson;

    @Column(name = "after_evidence_refs_json", columnDefinition = "text")
    private String afterEvidenceRefsJson;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "authored_by_id", nullable = false)
    private User authoredBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "locked", nullable = false)
    private boolean locked = false;

    @OneToMany(mappedBy = "reportVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaintenancePostCheck> postChecks = new ArrayList<>();
}
