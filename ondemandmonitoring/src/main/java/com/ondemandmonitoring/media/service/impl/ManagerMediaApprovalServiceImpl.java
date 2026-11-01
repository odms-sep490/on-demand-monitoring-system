package com.ondemandmonitoring.media.service.impl;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.domain.MediaNotificationOutbox;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.dto.response.OperatorMissionMediaResponse;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.repository.MediaNotificationOutboxRepository;
import com.ondemandmonitoring.media.service.IManagerMediaApprovalService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ManagerMediaApprovalServiceImpl implements IManagerMediaApprovalService {

    static String AVAILABLE_EVENT = "CUSTOMER_MEDIA_AVAILABLE";

    MediaAssetRepository media;
    MediaNotificationOutboxRepository outbox;
    IMediaObjectStorage storage;
    MediaWorkflowMapper mapper;
    IMissionMediaEvidenceGuard missionLock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OperatorMissionMediaResponse> listPending(String missionId, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "capturedAt", "id"));
        return PageResponse.from(media.findByMissionIdAndMediaStatus(
                missionId,
                MediaStatus.PENDING_MANAGER_APPROVAL,
                pageable).map(this::toResponse));
    }

    @Override
    @Transactional
    public OperatorMissionMediaResponse approve(String missionId, String mediaId) {
        // Promoting an already captured object to AVAILABLE does not alter the
        // approved evidence package. Keep this recovery path open for legacy
        // results that were approved while their media was still pending.
        MediaAsset asset = pendingAsset(missionId, mediaId, false);
        Instant now = Instant.now();
        asset.setMediaStatus(MediaStatus.AVAILABLE);
        asset.setAvailableAt(now);
        MediaAsset saved = media.save(asset);

        if (!outbox.existsByMediaIdAndEventType(saved.getId(), AVAILABLE_EVENT)) {
            MediaNotificationOutbox notification = new MediaNotificationOutbox();
            notification.setMedia(saved);
            notification.setMissionId(saved.getMissionId());
            notification.setEventType(AVAILABLE_EVENT);
            outbox.save(notification);
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public OperatorMissionMediaResponse reject(String missionId, String mediaId) {
        MediaAsset asset = pendingAsset(missionId, mediaId, true);
        asset.setMediaStatus(MediaStatus.REJECTED);
        asset.setAvailableAt(null);
        return toResponse(media.save(asset));
    }

    private MediaAsset pendingAsset(String missionId, String mediaId, boolean changesEvidenceDecision) {
        missionLock.lock(mediaId);
        if (changesEvidenceDecision) missionLock.requireMutable(missionId, mediaId);
        return media.findByIdForUpdate(mediaId)
                .filter(asset -> asset.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE)
                .filter(asset -> asset.getOrphanCleanupRequestedAt() == null)
                .filter(asset -> missionId.equals(asset.getMissionId()))
                .filter(asset -> asset.getMediaStatus() == MediaStatus.PENDING_MANAGER_APPROVAL)
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
    }

    private OperatorMissionMediaResponse toResponse(MediaAsset asset) {
        return mapper.toOperatorResponse(
                asset,
                storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key()),
                Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()));
    }
}
