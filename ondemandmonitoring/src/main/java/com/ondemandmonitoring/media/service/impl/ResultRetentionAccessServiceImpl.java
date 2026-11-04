package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResultRetentionAccessServiceImpl implements IResultRetentionAccessService {
    private final Clock clock;
    private final MediaAssetRepository mediaAssets;
    private final com.ondemandmonitoring.delivery.repository.ResultRevisionMediaHoldRepository revisionHolds;

    @Autowired
    public ResultRetentionAccessServiceImpl(MediaAssetRepository mediaAssets, Clock clock,
            com.ondemandmonitoring.delivery.repository.ResultRevisionMediaHoldRepository revisionHolds) {
        this.mediaAssets = mediaAssets;
        this.revisionHolds = revisionHolds;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireAccessibleForRevision(MediaAsset asset, String caseId) {
        if (!revisionHolds.belongsToActiveCase(asset.getId(), caseId)) {
            requireAccessible(asset);
            return;
        }
        if (asset.getStorageStatus() != MediaStorageStatus.AVAILABLE)
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND, "Media is not available for revision review");
    }

    @Override
    @Transactional(readOnly = true)
    public void requireAccessible(MediaAsset asset) {
        Instant expiresAt = mediaAssets.findResultExpiresAtByMediaId(asset.getId()).orElse(null);
        if (expiresAt != null && !Instant.now(clock).isBefore(expiresAt)) {
            String message = asset.getStorageStatus() == MediaStorageStatus.DELETED
                    ? "Kết quả đã hết thời hạn lưu trữ và đã được xóa khỏi hệ thống lưu trữ."
                    : "Kết quả đã hết thời hạn lưu trữ.";
            throw new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED, message);
        }
        if (asset.getStorageStatus() == MediaStorageStatus.DELETED) {
            throw new ApiException(ErrorCode.RESULT_RETENTION_EXPIRED,
                    "Kết quả đã được xóa khỏi hệ thống lưu trữ.");
        }
        if (asset.getStorageStatus() != MediaStorageStatus.AVAILABLE) {
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND, "Media storage cleanup is in progress.");
        }
    }
}
