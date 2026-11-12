package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.MediaUploadChannel;
import com.ondemandmonitoring.media.enums.MediaStorageStatus;
import com.ondemandmonitoring.media.dto.response.OperatorMissionMediaResponse;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository;
import com.ondemandmonitoring.media.repository.ManualUploadTaskRepository;
import com.ondemandmonitoring.media.service.IOperatorMissionMediaService;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
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
@Transactional(readOnly = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OperatorMissionMediaServiceImpl implements IOperatorMissionMediaService {

    IMissionMediaAccessService missionAccess;
    MediaAssetRepository media;
    MediaUploadAttemptRepository attempts;
    ManualUploadTaskRepository manualTasks;
    IMediaObjectStorage storage;
    MediaDerivativeService derivatives;
    MediaWorkflowMapper mapper;

    @Override
    public PageResponse<OperatorMissionMediaResponse> listAvailable(String missionId, int page, int size) {
        String canonicalId = missionAccess.authorizeOperator(missionId).getId();
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "capturedAt", "id"));
        return PageResponse.from(media.findByMissionIdAndMediaStatus(canonicalId,
                MediaStatus.AVAILABLE, pageable).map(this::toResponse));
    }

    @Override
    public PageResponse<OperatorMissionMediaResponse> listWorkspace(String missionId, int page, int size) {
        String canonicalId = missionAccess.authorizeOperator(missionId).getId();
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "capturedAt", "id"));
        var assets = media.findMissionCandidates(canonicalId, pageable);
        var ids = assets.getContent().stream().map(MediaAsset::getId).toList();
        var latestAttempts = ids.isEmpty() ? java.util.List.<com.ondemandmonitoring.media.domain.MediaUploadAttempt>of()
                : attempts.findLatestByMediaIds(ids);
        var attemptByMediaId = latestAttempts.stream().collect(java.util.stream.Collectors.toMap(
                attempt -> attempt.getMedia().getId(), attempt -> attempt, (first, ignored) -> first));
        var taskByMediaId = ids.isEmpty() ? java.util.Map.<String, com.ondemandmonitoring.media.domain.ManualUploadTask>of()
                : manualTasks.findByMediaIdIn(ids).stream().collect(java.util.stream.Collectors.toMap(
                        task -> task.getMedia().getId(), task -> task, (first, ignored) -> first));
        var staffCreatedIds = ids.isEmpty() ? java.util.Set.<String>of()
                : attempts.findInitialByMediaIds(ids).stream()
                    .filter(attempt -> attempt.getUploadChannel() == MediaUploadChannel.STAFF_FILE)
                    .map(attempt -> attempt.getMedia().getId()).collect(java.util.stream.Collectors.toSet());
        var mapped = assets.map(asset -> {
            var response = toWorkspaceResponse(asset);
            response.setStaffCreated(staffCreatedIds.contains(asset.getId()));
            var attempt = attemptByMediaId.get(asset.getId());
            if (attempt != null) {
                response.setUploadChannel(attempt.getUploadChannel() == null
                        ? MediaUploadChannel.FLIGHT_CONTROLLER : attempt.getUploadChannel());
                response.setAttemptNumber(attempt.getAttemptNumber());
                response.setFailureCode(attempt.getFailureCode());
                response.setFailureMessage(attempt.getFailureMessage());
            }
            var task = taskByMediaId.get(asset.getId());
            if (task != null && task.getStatus() == com.ondemandmonitoring.media.enums.ManualUploadTaskStatus.OPEN) {
                response.setManualTaskId(task.getId());
            }
            response.setLocalMediaId(asset.getLocalMediaId());
            response.setChecksumSha256(asset.getChecksumSha256());
            return response;
        });
        return PageResponse.from(mapped);
    }

    @Override
    public OperatorMissionMediaResponse getAvailable(String missionId, String mediaId) {
        String canonicalId = missionAccess.authorizeOperator(missionId).getId();
        MediaAsset asset = media.findById(mediaId)
                .filter(item -> canonicalId.equals(item.getMissionId()))
                .filter(item -> item.getMediaStatus() == MediaStatus.AVAILABLE)
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        return toResponse(asset);
    }

    private OperatorMissionMediaResponse toResponse(MediaAsset asset) {
        return mapper.toOperatorResponse(asset,
                storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key()),
                derivatives.thumbnailUrl(asset),
                derivatives.previewUrl(asset),
                Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()));
    }

    private OperatorMissionMediaResponse toWorkspaceResponse(MediaAsset asset) {
        boolean objectReady = asset.getStorageStatus() == MediaStorageStatus.AVAILABLE
                && asset.getValidatedAt() != null
                && java.util.Set.of(MediaStatus.AVAILABLE, MediaStatus.PENDING_MANAGER_APPROVAL, MediaStatus.REJECTED)
                        .contains(asset.getMediaStatus());
        if (!objectReady) {
            return mapper.toOperatorResponse(asset, null, null, null, null);
        }
        return mapper.toOperatorResponse(asset,
                storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key()),
                derivatives.thumbnailUrl(asset), derivatives.previewUrl(asset),
                Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()));
    }
}
