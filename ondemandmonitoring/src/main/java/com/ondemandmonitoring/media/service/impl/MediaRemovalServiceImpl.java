package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository;
import com.ondemandmonitoring.media.dto.request.RemoveMediaRequest;
import com.ondemandmonitoring.media.dto.response.MediaRemovalResponse;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.*;
import com.ondemandmonitoring.media.service.IMediaAuditService;
import com.ondemandmonitoring.media.service.IMediaRemovalService;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MediaRemovalServiceImpl implements IMediaRemovalService {
    private final IMissionMediaAccessService access;
    private final IMissionMediaEvidenceGuard guard;
    private final MediaAssetRepository media;
    private final MediaUploadAttemptRepository attempts;
    private final ManualUploadTaskRepository tasks;
    private final ResultReviewRoundEvidenceRepository publishedEvidence;
    private final IMediaAuditService audit;
    private final AuthenticatedUserResolver currentUser;

    @Override
    @Transactional
    public MediaRemovalResponse remove(String missionId, String mediaId, RemoveMediaRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Vui lòng nhập lý do gỡ media (tối đa 500 ký tự).");
        }
        var mission = access.authorizeOperator(missionId);
        guard.lockMission(mission.getId());
        access.requireUploadPermission(mission.getId());
        var asset = media.findByIdForUpdate(mediaId)
                .filter(item -> mission.getId().equals(item.getMissionId()))
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        // A replay is successful, but must never enqueue cleanup or create a second audit entry.
        if (asset.getMediaStatus() == MediaStatus.REMOVED) {
            return new MediaRemovalResponse(mediaId, true, asset.getDeletedFromStorageAt() == null);
        }
        if (!Objects.equals(asset.getVersion(), request.expectedVersion())) {
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        }
        var first = attempts.findFirstByMediaIdOrderByAttemptNumberAsc(mediaId);
        if (first.isEmpty() || first.get().getUploadChannel() != MediaUploadChannel.STAFF_FILE) {
            throw new ApiException(ErrorCode.MEDIA_EVIDENCE_PROTECTED, "Chỉ được gỡ media được tạo từ file upload thủ công.");
        }
        if (asset.isPreviewSelected() || guard.hasEvidenceOrResultReferences(mediaId)
                || publishedEvidence.existsByMediaAsset_Id(mediaId)) {
            throw new ApiException(ErrorCode.MEDIA_EVIDENCE_PROTECTED,
                    "Media đang gắn checklist, kết quả hoặc lịch sử bàn giao. Hãy gỡ liên kết nếu được phép trước khi gỡ media.");
        }
        if (asset.getStorageStatus() != MediaStorageStatus.AVAILABLE) {
            throw new ApiException(ErrorCode.MEDIA_EVIDENCE_PROTECTED, "Media đang được xử lý lưu trữ. Hãy làm mới và thử lại.");
        }
        var now = Instant.now();
        asset.setMediaStatus(MediaStatus.REMOVED);
        asset.setStorageStatus(MediaStorageStatus.DELETE_PENDING);
        asset.setOrphanCleanupRequestedAt(now);
        asset.setOrphanCleanupClaimedAt(null);
        asset.setStorageDeletionError(null);
        tasks.findByMediaId(mediaId).ifPresent(task -> {
            task.setStatus(ManualUploadTaskStatus.RESOLVED);
            task.setResolvedAt(now);
        });
        audit.record(asset, null, currentUser.getCurrentUserId(), MediaAuditAction.REMOVED, request.reason().strip());
        // No network I/O here. The committed tombstone drives the existing leased cleanup worker.
        return new MediaRemovalResponse(mediaId, true, true);
    }
}
