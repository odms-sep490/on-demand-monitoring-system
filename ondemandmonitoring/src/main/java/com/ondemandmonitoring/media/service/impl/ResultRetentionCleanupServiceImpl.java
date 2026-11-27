package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
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
    private final com.ondemandmonitoring.order.repository.OrderRepository orders;
    private final MediaObjectCleanup cleanup;
    private final ResultRetentionProperties properties;
    private final PlatformTransactionManager transactionManager;

    @Override
    public CleanupSummary cleanupExpiredResults() {
        Instant startedAt = Instant.now();
        Instant now = startedAt;
        int batchSize = Math.max(1, properties.getBatchSize());
        List<String> ids = media.findExpiredStorageCandidateIds(now,
                List.of(MediaStorageStatus.AVAILABLE, MediaStorageStatus.DELETE_PENDING, MediaStorageStatus.DELETE_FAILED),
                PageRequest.of(0, batchSize)).getContent();
        int deleted = 0;
        int failed = 0;
        for (String id : ids) {
            try {
                ClaimedAsset claimed = claim(id, now);
                if (claimed == null) continue;
                if (cleanup.completed(claimed.taskIds())) {
                    markDeleted(id, Instant.now());
                    deleted++;
                } else if (cleanup.failed(claimed.taskIds())) {
                    markFailed(id, "RESULT_RETENTION_CLEANUP_FAILED");
                    failed++;
                }
            } catch (RuntimeException exception) {
                // Atomic retirement/enqueue rolled back; keep the candidate available for reconciliation.
                failed++;
                log.warn("Expired result reconciliation failed. mediaId={}, reason={}", id,
                        exception.getClass().getSimpleName());
            }
        }
        return new CleanupSummary(ids.size(), deleted, failed,
                java.time.Duration.between(startedAt, Instant.now()).toMillis());
    }

    private ClaimedAsset claim(String id, Instant now) {
        return tx().execute(status -> {
            String orderId = media.findOrderIdByMediaId(id).orElse(null);
            if (orderId == null || orders.findByIdForUpdate(orderId).isEmpty()) return null;
            return media.findByIdForUpdate(id).filter(asset -> {
                Instant expiresAt = asset.getMission().getOrder().getResultExpiresAt();
                return expiresAt != null && !expiresAt.isAfter(now)
                        && (asset.getStorageStatus() == MediaStorageStatus.AVAILABLE
                        || asset.getStorageStatus() == MediaStorageStatus.DELETE_PENDING
                        || asset.getStorageStatus() == MediaStorageStatus.DELETE_FAILED);
            }).map(asset -> {
                asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
                asset.setStorageDeletionError(null);
                media.save(asset);
                var keys = new java.util.LinkedHashSet<String>();
                keys.add(asset.getS3Key());
                if (asset.getThumbnailKey() != null) keys.add(asset.getThumbnailKey());
                if (asset.getPreviewKey() != null) keys.add(asset.getPreviewKey());
                keys.removeIf(key -> key == null || key.isBlank());
                return new ClaimedAsset(cleanup.enqueue(asset.getS3Bucket(), keys, id));
            }).orElse(null);
        });
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

    private record ClaimedAsset(java.util.List<String> taskIds) {}
}
