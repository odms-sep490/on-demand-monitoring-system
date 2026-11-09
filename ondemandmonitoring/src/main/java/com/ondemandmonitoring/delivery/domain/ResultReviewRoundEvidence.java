package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.enums.DeliveryEvidenceChangeType;
import com.ondemandmonitoring.media.domain.MediaAsset;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "result_review_round_evidence",
        uniqueConstraints = @UniqueConstraint(name = "uk_result_review_round_evidence_media", columnNames = {"round_item_id", "media_asset_id"}),
        indexes = @Index(name = "ix_result_review_round_evidence_item_media", columnList = "round_item_id,media_asset_id"))
@Getter @Setter
public class ResultReviewRoundEvidence extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_item_id", nullable = false, updatable = false)
    private ResultReviewRoundItem roundItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false, updatable = false)
    private MediaAsset mediaAsset;

    @Column(name = "execution_id", length = 255, updatable = false)
    private String executionId;

    @Column(name = "evidence_id", length = 255, updatable = false)
    private String evidenceId;

    @Column(name = "file_name_snapshot", length = 1000, updatable = false)
    private String fileNameSnapshot;

    @Column(name = "media_type_snapshot", length = 100, updatable = false)
    private String mediaTypeSnapshot;

    @Column(name = "captured_at_snapshot", updatable = false)
    private Instant capturedAtSnapshot;

    @Column(name = "source_round_evidence_id", length = 255, updatable = false)
    private String sourceRoundEvidenceId;

    @Column(name = "supersedes_round_evidence_id", length = 255, updatable = false)
    private String supersedesRoundEvidenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30, updatable = false)
    private DeliveryEvidenceChangeType changeType = DeliveryEvidenceChangeType.INITIAL;
}
