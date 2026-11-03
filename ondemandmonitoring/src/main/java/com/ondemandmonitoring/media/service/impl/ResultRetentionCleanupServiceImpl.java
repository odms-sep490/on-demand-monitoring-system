package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionCleanupService;
import com.ondemandmonitoring.media.infrastructure.s3.ResultRetentionProperties;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResultRetentionCleanupServiceImpl implements IResultRetentionCleanupService {
    private final MediaAssetRepository media;
    private final com.ondemandmonitoring.delivery.repository.ResultRevisionMediaHoldRepository revisionHolds;
    private final IMediaObjectStorage storage;
    private final ResultRetentionProperties properties;
    private final PlatformTransactionManager transactionManager;

    @Override
    public CleanupSummary cleanupExpiredResults() {
        Instant startedAt = Instant.now();
        Instant now = startedAt;
        int batchSize = Math.max(1, properties.getBatchSize());
        List<String> ids = media.findExpiredStorageCandidateIds(now,
                List.of(MediaStorageStatus.AVAILABLE, MediaStorageStatus.DELETE_FAILED),
                PageRequest.of(0, batchSize)).getContent();
        int deleted = 0;
        int failed = 0;
        for (String id : ids) {
            ClaimedAsset claimed = claim(id, now);
            if (claimed == null) continue;
            try {
                storage.delete(claimed.bucket(), claimed.key());
                markDeleted(id, Instant.now());
                deleted++;
            } catch (RuntimeException exception) {
                markFailed(id, sanitize(exception));
                failed++;
                log.warn("Expired result deletion failed. mediaId={}, reason={}", id,
                        exception.getClass().getSimpleName());
            }
        }
        return new CleanupSummary(ids.size(), deleted, failed,
                java.time.Duration.between(startedAt, Instant.now()).toMillis());
    }

    private ClaimedAsset claim(String id, Instant now) {
        return tx().execute(status -> media.findByIdForUpdate(id).filter(asset -> {
            Instant expiresAt = asset.getMission().getOrder().getResultExpiresAt();
            return expiresAt != null && !expiresAt.isAfter(now)
                    && !revisionHolds.hasActiveHold(asset.getId(), now)
                    && (asset.getStorageStatus() == MediaStorageStatus.AVAILABLE
                    || asset.getStorageStatus() == MediaStorageStatus.DELETE_FAILED);
        }).map(asset -> {
            asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
            asset.setStorageDeletionError(null);
            media.save(asset);
            return new ClaimedAsset(asset.getS3Bucket(), asset.getS3Key());
        }).orElse(null));
    }

    private void markDeleted(String id, Instant deletedAt) {
        tx().executeWithoutResult(status -> media.findByIdForUpdate(id).ifPresent(asset -> {
            if (asset.getStorageStatus() != MediaStorageStatus.DELETE_PENDING) return;
            asset.setStorageStatus(MediaStorageStatus.DELETED);
            asset.setDeletedFromStorageAt(deletedAt);
            asset.setStorageDeletionError(null);
            media.save(asset);
        }));
    }

    private void markFailed(String id, String error) {
        tx().executeWithoutResult(status -> media.findByIdForUpdate(id).ifPresent(asset -> {
            if (asset.getStorageStatus() != MediaStorageStatus.DELETE_PENDING) return;
            asset.setStorageStatus(MediaStorageStatus.DELETE_FAILED);
            asset.setStorageDeletionError(error);
            media.save(asset);
        }));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String sanitize(RuntimeException exception) {
        String value = exception.getClass().getSimpleName();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record ClaimedAsset(String bucket, String key) {}
}
