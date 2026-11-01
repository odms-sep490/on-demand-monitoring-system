package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaOrphanCleanupServiceImpl implements IMediaOrphanCleanupService {
    private final MediaAssetRepository media;
    private final MediaUploadAttemptRepository attempts;
    private final IMissionMediaEvidenceGuard guard;
    private final IMediaObjectStorage storage;
    private final PlatformTransactionManager transactions;
    @Value("${app.media.orphan-cleanup.delay-seconds:300}") private long delaySeconds = 300;
    @Value("${app.media.orphan-cleanup.upload-grace-seconds:900}") private long graceSeconds = 900;
    @Value("${app.media.orphan-cleanup.claim-timeout-seconds:600}") private long claimSeconds = 600;

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void request(String id) {
        var asset = media.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        if (!protectedAsset(asset) && !guard.hasEvidenceOrResultReferences(id)
                && asset.getStorageStatus() != MediaStorageStatus.DELETED)
            asset.setOrphanCleanupRequestedAt(Instant.now());
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(String id) {
        var asset = media.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        if (asset.getStorageStatus() != MediaStorageStatus.AVAILABLE)
            throw new ApiException(ErrorCode.MEDIA_EVIDENCE_PROTECTED, "Storage cleanup is in progress; refresh before reattaching");
        asset.setOrphanCleanupRequestedAt(null);
        asset.setOrphanCleanupClaimedAt(null);
    }

    @Override public int cleanup() {
        // PostgreSQL stores timestamptz at microsecond precision; the lease token
        // must survive a round trip before finish compares it with the claim.
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var ids = media.findOrphanCleanupCandidates(now.minusSeconds(Math.max(0, delaySeconds)), PageRequest.of(0, 50));
        int deleted = 0;
        for (String id : ids) {
            Claim claimed = new TransactionTemplate(transactions).execute(tx -> claim(id, now));
            if (claimed == null) continue;
            try {
                for (var upload : claimed.multipart()) storage.abortMultipartUpload(upload.key(), upload.uploadId());
                for (String key : claimed.keys()) storage.delete(claimed.bucket(), key);
                finish(id, claimed.claimedAt(), true);
                deleted++;
            } catch (RuntimeException failure) {
                finish(id, claimed.claimedAt(), false);
                log.warn("Orphan media cleanup failed; will retry. mediaId={}, failure={}", id, failure.getClass().getSimpleName());
            }
        }
        return deleted;
    }

    private Claim claim(String id, Instant now) {
        guard.lock(id); // Same Mission -> Media order as attach/validation.
        var asset = media.findByIdForUpdate(id).orElse(null);
        if (asset == null || asset.getOrphanCleanupRequestedAt() == null || asset.getStorageStatus() == MediaStorageStatus.DELETED) return null;
        if (asset.getOrphanCleanupRequestedAt().plusSeconds(Math.max(0, delaySeconds)).isAfter(now)) return null;
        if (asset.getStorageStatus() == MediaStorageStatus.DELETE_PENDING
                && asset.getOrphanCleanupClaimedAt() != null
                && asset.getOrphanCleanupClaimedAt().plusSeconds(claimSeconds).isAfter(now)) return null;
        if (protectedAsset(asset) || guard.hasEvidenceOrResultReferences(id)) {
            asset.setOrphanCleanupRequestedAt(null);
            return null;
        }
        var history = attempts.findByMediaIdOrderByAttemptNumberDesc(id);
        var multipart = new ArrayList<Multipart>();
        // Signed URLs remain usable after a successful PUT. Wait for every known
        // URL to expire, plus a grace period for transfers already in progress.
        if (history.stream().anyMatch(a -> a.getExpiresAt() != null
                ? a.getExpiresAt().plusSeconds(graceSeconds).isAfter(now)
                : a.getStatus() == UploadAttemptStatus.PENDING || a.getStatus() == UploadAttemptStatus.UPLOADED)) return null;
        if ((asset.getMediaStatus() == MediaStatus.VALIDATING || asset.getMediaStatus() == MediaStatus.UPLOADING)
                && asset.getUpdatedAt() != null && asset.getUpdatedAt().plusSeconds(graceSeconds).isAfter(now)) return null;
        for (var attempt : history) {
          if (attempt.getStatus() == UploadAttemptStatus.PENDING || attempt.getStatus() == UploadAttemptStatus.UPLOADED || "ORPHAN_CLEANUP".equals(attempt.getFailureCode())) {
            if (attempt.getMultipartUploadId() != null) {
                multipart.add(new Multipart(attempt.getStorageKey(), attempt.getMultipartUploadId()));
            }
            attempt.setStatus(UploadAttemptStatus.FAILED);
            attempt.setFailureCode("ORPHAN_CLEANUP");
            attempt.setCompletedAt(now);
          }
        }
        var keys = new LinkedHashSet<String>();
        history.forEach(a -> keys.add(a.getStorageKey()));
        keys.add(asset.getS3Key());
        keys.removeIf(k -> k == null || k.isBlank());
        asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        asset.setMediaStatus(MediaStatus.RETRY_REQUIRED);
        asset.setValidatedAt(null);
        asset.setOrphanCleanupClaimedAt(now);
        asset.setStorageDeletionError(null);
        return new Claim(asset.getS3Bucket(), List.copyOf(keys), List.copyOf(multipart), now);
    }

    private void finish(String id, Instant claimedAt, boolean success) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> media.findByIdForUpdate(id).ifPresent(asset -> {
            if (asset.getStorageStatus() != MediaStorageStatus.DELETE_PENDING || !Objects.equals(claimedAt, asset.getOrphanCleanupClaimedAt())) return;
            asset.setStorageStatus(success ? MediaStorageStatus.DELETED : MediaStorageStatus.DELETE_FAILED);
            asset.setStorageDeletionError(success ? null : "ORPHAN_CLEANUP_FAILED");
            if (success) {
                asset.setDeletedFromStorageAt(Instant.now());
                asset.setValidatedAt(null);
                asset.setMediaStatus(MediaStatus.RETRY_REQUIRED);
                asset.setValidationError("ORPHAN_OBJECT_DELETED");
            }
        }));
    }

    private boolean protectedAsset(MediaAsset asset) {
        return asset.getMediaStatus() == MediaStatus.AVAILABLE || asset.getAvailableAt() != null || asset.isPreviewSelected();
    }
    private record Multipart(String key, String uploadId) {}
    private record Claim(String bucket, List<String> keys, List<Multipart> multipart, Instant claimedAt) {}
}
