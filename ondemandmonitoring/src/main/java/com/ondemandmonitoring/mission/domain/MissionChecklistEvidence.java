package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.media.domain.MediaAsset;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "mission_checklist_evidence", indexes = {
    @Index(name = "ix_evidence_mission_execution", columnList = "mission_id,mission_checklist_execution_id"),
    @Index(name = "ix_evidence_media", columnList = "media_asset_id")
})
@Getter
@Setter
public class MissionChecklistEvidence extends BaseEntity {
    // The deployment migration owns the two composite same-mission FKs and partial unique index.
    @Column(name = "mission_id", nullable = false, updatable = false)
    private String missionId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_checklist_execution_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private MissionChecklistExecution missionChecklistExecution;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private MediaAsset mediaAsset;
    @Column(name = "attached_by", nullable = false, updatable = false)
    private String attachedBy;
    @Column(name = "attached_at", nullable = false, updatable = false)
    private Instant attachedAt;
    @Column(name = "selected_for_customer", nullable = false, columnDefinition = "boolean default false")
    private boolean selectedForCustomer = false;
    @Column(name = "selected_by", length = 255)
    private String selectedBy;
    @Column(name = "selected_at")
    private Instant selectedAt;
    @Column(length = 1000)
    private String note;
    @Column(name = "detached_by")
    private String detachedBy;
    @Column(name = "detached_at")
    private Instant detachedAt;
    @Column(name = "detach_reason", length = 1000)
    private String detachReason;
}
