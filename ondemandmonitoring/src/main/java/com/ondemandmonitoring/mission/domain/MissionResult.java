package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.enums.MissionResultStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@Entity
@Table(name = "mission_results")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MissionResult extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false, unique = true)
    Mission mission;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    MissionResultStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 40)
    MissionResultApprovalStatus approvalStatus;

    /** True only after the inspector freezes the selected customer bundle. */
    @Column(name = "inspector_confirmed", nullable = false, columnDefinition = "boolean default false")
    boolean inspectorConfirmed;

    @Column(name = "started_at")
    Instant startedAt;

    @Column(name = "ended_at")
    Instant endedAt;

    @Column(name = "completed_at", nullable = false)
    Instant completedAt;

    @Column(name = "submitted_at")
    Instant submittedAt;

    @Column(name = "approved_at")
    Instant approvedAt;

    @Column(name = "rejected_at")
    Instant rejectedAt;

    @Column(name = "duration_seconds")
    Long durationSeconds;

    @Column(name = "media_count", nullable = false)
    Integer mediaCount;

    @Column(name = "summary", length = 1000)
    String summary;

    @Lob
    @Column(name = "notes")
    String notes;

    @Column(name = "created_by", length = 100)
    String createdBy;

    @Column(name = "reviewed_by", length = 100)
    String reviewedBy;

    @Column(name = "review_note", length = 1000)
    String reviewNote;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "mission_result_media",
            joinColumns = @JoinColumn(name = "mission_result_id"),
            inverseJoinColumns = @JoinColumn(name = "media_id"))
    List<MediaAsset> mediaFiles = new ArrayList<>();
}
