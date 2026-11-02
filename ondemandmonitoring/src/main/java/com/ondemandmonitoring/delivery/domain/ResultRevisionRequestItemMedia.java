package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "result_revision_request_item_media",
        uniqueConstraints = @UniqueConstraint(name = "uk_result_revision_request_item_media_pair", columnNames = {"request_item_id", "round_evidence_id"}),
        indexes = @Index(name = "ix_result_revision_request_item_media_evidence", columnList = "round_evidence_id"))
@Getter @Setter
public class ResultRevisionRequestItemMedia extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_item_id", nullable = false, updatable = false)
    private ResultRevisionRequestItem requestItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_evidence_id", nullable = false, updatable = false)
    private ResultReviewRoundEvidence roundEvidence;
}
