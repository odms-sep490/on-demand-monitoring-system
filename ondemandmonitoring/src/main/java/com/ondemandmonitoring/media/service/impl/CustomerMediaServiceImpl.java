package com.ondemandmonitoring.media.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.dto.response.CustomerMediaNotificationResponse;
import com.ondemandmonitoring.media.dto.response.CustomerMediaResponse;
import com.ondemandmonitoring.media.dto.response.CustomerMissionMediaStatusResponse;
import com.ondemandmonitoring.media.mapper.MediaWorkflowMapper;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.repository.MediaNotificationOutboxRepository;
import com.ondemandmonitoring.media.service.ICustomerMediaService;
import com.ondemandmonitoring.mission.service.IMissionMediaAccessService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import java.util.List;
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
    MediaNotificationOutboxRepository notifications;
    IMediaObjectStorage storage;
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
    public List<CustomerMediaNotificationResponse> listNotifications(String missionId) {
        String canonicalId = missionAccess.authorizeCustomer(missionId);
        var visibleIds = publishedMediaIds(List.of(canonicalId));
        return notifications.findByMedia_MissionIdOrderByCreatedAtDesc(canonicalId).stream()
                .filter(event -> event.getMedia().getMediaStatus() == MediaStatus.AVAILABLE)
                .filter(event -> visibleIds.contains(event.getMedia().getId()))
                .map(mapper::toNotificationResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerMediaNotificationResponse> listAllNotifications() {
        List<String> missionIds = ownMissionIds();
        if (missionIds.isEmpty())
            return List.of();
        var visibleIds = publishedMediaIds(missionIds);
        return notifications.findByMedia_MissionIdInOrderByCreatedAtDesc(missionIds).stream()
                .filter(event -> event.getMedia().getMediaStatus() == MediaStatus.AVAILABLE)
                .filter(event -> visibleIds.contains(event.getMedia().getId()))
                .map(mapper::toNotificationResponse)
                .toList();
    }

    private List<String> ownMissionIds() {
        return missionAccess.ownCustomerMissionIds();
    }

    private List<String> deliveredMissionIds(List<String> missionIds) {
        // Normal ineligibility must not be represented by an exception here. Catching an
        // exception from another transactional bean still marks the outer transaction as
        // rollback-only and causes UnexpectedRollbackException during commit.
        if (deliveryWorkflow == null) return missionIds;
        return deliveryWorkflow.originalAccessibleMissionIds(missionIds);
    }

    private java.util.Set<String> publishedMediaIds(List<String> missionIds) {
        return deliveryWorkflow == null ? java.util.Set.of() : deliveryWorkflow.publishedMediaAssetIds(missionIds);
    }

    private CustomerMediaResponse toResponse(MediaAsset asset, boolean originalsAvailable) {
        if (originalsAvailable) retentionAccess.requireAccessible(asset);
        return mapper.toCustomerResponse(asset,
                originalsAvailable ? storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key()) : null);
    }
}
