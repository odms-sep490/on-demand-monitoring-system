package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable evidence handoff; subsequent corrections create another submission. */
@Entity
@Table(name = "mission_evidence_submissions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_evidence_submission_revision", columnNames = {"mission_id", "revision"}),
    @UniqueConstraint(name = "uk_evidence_submission_command", columnNames = {"mission_id", "command_id"})})
@Getter
@Setter
public class MissionEvidenceSubmission extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false, updatable = false)
    private Mission mission;
    @Column(nullable = false, updatable = false)
    private long revision;
    @Column(name = "command_id", nullable = false, updatable = false, length = 100)
    private String commandId;
    @Column(name = "submitted_by", nullable = false, updatable = false)
    private String submittedBy;
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;
    @Column(name = "correction_id", updatable = false)
    private String correctionId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> snapshot;
}
