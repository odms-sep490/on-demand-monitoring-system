package com.ondemandmonitoring.delivery.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.media.domain.MediaAsset;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "result_revision_media_holds", uniqueConstraints =
        @UniqueConstraint(name = "uk_result_revision_media_hold", columnNames = {"case_id", "media_asset_id"}))
@Getter
@Setter
public class ResultRevisionMediaHold extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false, updatable = false)
    private ResultRevisionCase resultRevisionCase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_asset_id", nullable = false, updatable = false)
    private MediaAsset mediaAsset;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
