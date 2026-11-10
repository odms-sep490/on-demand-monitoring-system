package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.dto.response.CustomerMediaResponse;
import com.ondemandmonitoring.media.dto.response.OriginalMediaDownloadResponse;
import com.ondemandmonitoring.media.dto.response.MediaViewingResponse;
import com.ondemandmonitoring.media.dto.response.CustomerMissionMediaStatusResponse;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.ICustomerMediaService;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import java.util.List;
import java.time.Instant;
import com.ondemandmonitoring.common.api.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CustomerMediaServiceImpl implements ICustomerMediaService {

    IMissionMediaAccessService missionAccess;
    MediaAssetRepository media;
    IMediaObjectStorage storage;
    MediaDerivativeService derivatives;
    MediaWorkflowMapper mapper;
    IResultRetentionAccessService retentionAccess;
    @Autowired @NonFinal IDeliveryWorkflowService deliveryWorkflow;

    @Override
    @Transactional(readOnly = true)
    public CustomerMissionMediaStatusResponse getMissionMediaStatus(String missionId) {
        String canonicalId = missionAccess.authorizeCustomer(missionId);
        var visibleIds = publishedMediaIds(List.of(canonicalId));
        return CustomerMissionMediaStatusResponse.builder()
                .availableCount(visibleIds.size())
                .processingCount(media.countByMissionIdAndMediaStatusIn(canonicalId, List.of(
                        MediaStatus.UPLOAD_PENDING, MediaStatus.UPLOADING, MediaStatus.VALIDATING,
                        MediaStatus.RETRY_REQUIRED, MediaStatus.MANUAL_UPLOAD_REQUIRED,
                        MediaStatus.PENDING_MANAGER_APPROVAL)))
                .rejectedCount(media.countByMissionIdAndMediaStatus(canonicalId, MediaStatus.REJECTED))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CustomerMediaResponse> listAvailablePage(String missionId, int page, int size) {
        String canonicalId = missionAccess.authorizeCustomer(missionId);
        boolean originalsAvailable = deliveredMissionIds(List.of(canonicalId)).contains(canonicalId);
        var visibleIds = publishedMediaIds(List.of(canonicalId));
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "capturedAt", "id"));
        if (visibleIds.isEmpty()) return PageResponse.from(org.springframework.data.domain.Page.empty(pageable));
        return PageResponse.from(media.findByMissionIdAndIdInAndMediaStatus(
                canonicalId, visibleIds, MediaStatus.AVAILABLE, pageable).map(asset -> toResponse(asset, originalsAvailable)));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerMediaResponse getAvailableInMission(String missionId, String mediaId) {
        String canonicalId = missionAccess.authorizeCustomer(missionId);
        boolean originalsAvailable = deliveredMissionIds(List.of(canonicalId)).contains(canonicalId);
        var visibleIds = publishedMediaIds(List.of(canonicalId));
        return media.findById(mediaId)
                .filter(asset -> visibleIds.contains(asset.getId()))
                .filter(asset -> canonicalId.equals(asset.getMissionId()))
                .filter(asset -> asset.getMediaStatus() == MediaStatus.AVAILABLE)
                .map(asset -> toResponse(asset, originalsAvailable))
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerMediaResponse> listAvailable(String missionId) {
        String canonicalId = missionAccess.authorizeCustomer(missionId);
        boolean originalsAvailable = deliveredMissionIds(List.of(canonicalId)).contains(canonicalId);
        var visibleIds = publishedMediaIds(List.of(canonicalId));
        return media.findByMissionIdOrderByCapturedAtDesc(canonicalId).stream()
                .filter(asset -> visibleIds.contains(asset.getId()))
                .filter(asset -> asset.getMediaStatus() == MediaStatus.AVAILABLE)
                .map(asset -> toResponse(asset, originalsAvailable)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerMediaResponse> listAllAvailable() {
        List<String> missionIds = ownMissionIds();
        if (missionIds.isEmpty())
            return List.of();
        var delivered = new java.util.HashSet<>(deliveredMissionIds(missionIds));
        var visibleIds = publishedMediaIds(missionIds);
        return media.findByMissionIdInAndMediaStatusOrderByCapturedAtDesc(missionIds,
                MediaStatus.AVAILABLE)
                .stream().filter(asset -> visibleIds.contains(asset.getId()))
                .map(asset -> toResponse(asset, delivered.contains(asset.getMissionId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerMediaResponse getAvailable(String mediaId) {
        MediaAsset asset = media.findById(mediaId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        missionAccess.authorizeCustomer(asset.getMissionId());
        if (!publishedMediaIds(List.of(asset.getMissionId())).contains(asset.getId())) {
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
        }
        if (asset.getMediaStatus() != MediaStatus.AVAILABLE) {
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
        }
        return toResponse(asset, deliveredMissionIds(List.of(asset.getMissionId())).contains(asset.getMissionId()));
    }

    @Override
    @Transactional(readOnly = true)
    public OriginalMediaDownloadResponse createOriginalDownload(String mediaId) {
        MediaAsset asset = media.findById(mediaId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        String missionId = missionAccess.authorizeCustomer(asset.getMissionId());
        if (asset.getMediaStatus() != MediaStatus.AVAILABLE
                || !publishedMediaIds(List.of(missionId)).contains(asset.getId()))
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
        String orderId = media.findOrderIdByMediaId(mediaId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        if (deliveryWorkflow == null) throw new ApiException(ErrorCode.DELIVERABLE_NOT_RELEASED);
        deliveryWorkflow.requireOriginalAccessForMedia(orderId, mediaId);
        retentionAccess.requireAccessible(asset);
        requireStoredObject(asset);
        return new OriginalMediaDownloadResponse(asset.getId(), asset.getOriginalFileName(),
                storage.createPresignedDownloadUrl(asset.getS3Bucket(), asset.getS3Key(), asset.getOriginalFileName()),
                Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()));
    }

    private List<String> ownMissionIds() {
        return missionAccess.ownCustomerMissionIds();
    }

    @Override
    @Transactional(readOnly = true)
    public MediaViewingResponse createViewingFallback(String mediaId) {
        MediaAsset asset = media.findById(mediaId).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        String missionId = missionAccess.authorizeCustomer(asset.getMissionId());
        if (asset.getMediaStatus() != MediaStatus.AVAILABLE
                || !"S3".equals(asset.getStorageProvider())
                || asset.getS3Bucket() == null || asset.getS3Key() == null
                || !publishedMediaIds(List.of(missionId)).contains(mediaId))
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
        retentionAccess.requireAccessible(asset);

        for (String derivativeKey : derivatives.viewingDerivativeKeys(asset)) {
            var derivative = inspectDerivative(asset, derivativeKey);
            if (derivative != null) {
                long size = derivative.contentLength() == null ? 0L : derivative.contentLength();
                return new MediaViewingResponse(mediaId,
                        storage.createPresignedGetUrl(asset.getS3Bucket(), derivativeKey),
                        Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()), size);
            }
        }

        MediaDerivativeStatus derivativeStatus = asset.getDerivativeStatus();
        if (derivativeStatus == MediaDerivativeStatus.PENDING
                || (derivativeStatus == MediaDerivativeStatus.PROCESSING
                    && asset.getDerivativeAttemptCount() <= 1))
            throw new ApiException(ErrorCode.MEDIA_DERIVATIVE_PROCESSING);

        // Original viewing remains the recovery path only when no usable derivative exists.
        var stored = requireStoredObject(asset);
        long size = stored != null && stored.contentLength() != null ? stored.contentLength()
                : asset.getFileSize() == null ? 0L : asset.getFileSize();
        return new MediaViewingResponse(mediaId,
                storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key()),
                Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()),
                size);
    }

    private IMediaObjectStorage.StoredObjectInfo inspectDerivative(MediaAsset asset, String key) {
        try {
            return storage.inspect(asset.getS3Bucket(), key);
        } catch (software.amazon.awssdk.services.s3.model.S3Exception failure) {
            if (failure.statusCode() == 404) return null;
            throw failure;
        }
    }

    private IMediaObjectStorage.StoredObjectInfo requireStoredObject(MediaAsset asset) {
        if (!"S3".equals(asset.getStorageProvider())
                || asset.getS3Bucket() == null || asset.getS3Bucket().isBlank()
                || asset.getS3Key() == null || asset.getS3Key().isBlank())
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
        try {
            return storage.inspect(asset.getS3Bucket(), asset.getS3Key());
        } catch (software.amazon.awssdk.services.s3.model.S3Exception failure) {
            if (failure.statusCode() == 404) throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
            throw failure;
        }
    }

    private List<String> deliveredMissionIds(List<String> missionIds) {
        // Normal ineligibility must not be represented by an exception here. Catching an
        // exception from another transactional bean still marks the outer transaction as
        // rollback-only and causes UnexpectedRollbackException during commit.
        if (deliveryWorkflow == null) return List.of();
        return deliveryWorkflow.originalAccessibleMissionIds(missionIds);
    }

    private java.util.Set<String> publishedMediaIds(List<String> missionIds) {
        return deliveryWorkflow == null ? java.util.Set.of() : deliveryWorkflow.publishedMediaAssetIds(missionIds);
    }

    private CustomerMediaResponse toResponse(MediaAsset asset, boolean originalsAvailable) {
        retentionAccess.requireAccessible(asset);
        var response = mapper.toCustomerResponse(asset, null);
        response.setThumbnailUrl(derivatives.thumbnailUrl(asset));
        response.setPreviewUrl(derivatives.previewUrl(asset));
        response.setCanDownloadOriginal(originalsAvailable);
        return response;
    }
}
