package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.dto.response.OperatorMissionMediaResponse;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
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
}
