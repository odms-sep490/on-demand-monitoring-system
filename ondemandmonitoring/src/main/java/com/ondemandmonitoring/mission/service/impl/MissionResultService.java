package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionResult;
import com.ondemandmonitoring.mission.dto.request.MissionResultRequest;
import com.ondemandmonitoring.mission.dto.request.MissionResultReviewRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResultResponse;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.enums.MissionResultStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.mission.mapper.MissionResultMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import lombok.AccessLevel;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MissionResultService implements com.ondemandmonitoring.mission.service.IMissionResultService {

    MissionResultRepository missionResultRepository;
    MissionRepository missionRepository;
    MediaAssetRepository mediaAssetRepository;
    IMediaAssetService mediaAssetService;
    MissionResultMapper missionResultMapper;
    IOrderExecutionCompletionPolicy completionPolicy;
    IMissionChecklistExecutionService checklistExecutionService;
    AuthenticatedUserResolver currentUser;
    IMissionAuthorizationService authorization;
    @org.springframework.beans.factory.annotation.Autowired
    @lombok.experimental.NonFinal
    IDeliveryWorkflowService deliveryWorkflow;

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public MissionResultResponse getByMissionId(String missionId) {
        return missionResultRepository.findByMissionId(missionId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canSubmitMissionResult(#missionId)")
    public MissionResultResponse upsert(String missionId, MissionResultRequest request) {
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND,
                        "Mission not found: " + missionId));
        MissionRuntimeGate.requireFinalizedStructure(mission);
        checklistExecutionService.requireMaterialized(mission);
        if (!authorization.canSubmitMissionResult(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (mission.getStatus() != MissionStatus.COMPLETED)
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Complete operational mission before submitting results");
        checklistExecutionService.requireReadyForSubmission(mission);
        MissionResult result = missionResultRepository.findByMissionId(missionId)
                .orElseGet(() -> {
                    MissionResult created = new MissionResult();
                    created.setMission(mission);
                    created.setCreatedBy(currentUser.getCurrentUserId());
                    return created;
                });
        if (result.getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                || result.getApprovalStatus() == MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL)
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
        applyRequest(result, mission, request);
        return toResponse(missionResultRepository.save(result));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public PageResponse<MissionResultResponse> listPendingManagerApproval(int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "submittedAt", "id"));
        return PageResponse.from(missionResultRepository
                .findByApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, pageable)
                .map(this::toResponse));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionResultResponse approve(String resultId, MissionResultReviewRequest request) {
        String orderId = missionResultRepository.findOrderIdByResultId(resultId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var aggregate = completionPolicy.lockAggregate(orderId);
        MissionResult result = missionResultRepository.findById(resultId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!orderId.equals(result.getMission().getOrder().getId())
                || aggregate.missions().stream().noneMatch(m -> m.getId().equals(result.getMission().getId())))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        if (result.getApprovalStatus() == MissionResultApprovalStatus.APPROVED) {
            completionPolicy.completeIfReady(aggregate);
            return toResponse(result);
        }
        if (result.getApprovalStatus() != MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL)
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Pending mission result not found: " + resultId);
        if (!ExecutionPlanCoverageService.effective(result.getMission()))
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID);
        MissionRuntimeGate.requireFinalizedStructure(result.getMission());
        checklistExecutionService.requireMaterialized(result.getMission());
        checklistExecutionService.requireReadyForFinalApproval(result.getMission());
        if (result.getMission().getStatus() != MissionStatus.COMPLETED)
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID);
        boolean unfinishedMediaReview = mediaAssetRepository
                .findByMissionIdOrderByCapturedAtDesc(result.getMission().getId()).stream()
                .filter(asset -> asset.getOrphanCleanupRequestedAt() == null
                        && asset.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE)
                .anyMatch(asset -> asset.getMediaStatus() != MediaStatus.AVAILABLE
                        && asset.getMediaStatus() != MediaStatus.REJECTED);
        if (unfinishedMediaReview) {
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY,
                    "Approve or reject every mission media file before approving the mission result");
        }
        requireOrderMediaQuantity(aggregate, result, request);
        Instant now = Instant.now();
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        result.setApprovedAt(now);
        result.setRejectedAt(null);
        result.setReviewedBy(currentUser.getCurrentUserId());
        result.setReviewNote(request == null ? null : request.getNote());
        attachMissionMedia(result, result.getMission().getId());
        var saved = missionResultRepository.saveAndFlush(result);
        completionPolicy.completeIfReady(aggregate);
        if (deliveryWorkflow != null) deliveryWorkflow.markReadyForManagerReview(result.getMission().getOrder().getId());
        return toResponse(saved);
    }

    /**
     * Order-level check: a photo may legitimately serve several checklists, so every checklist can be "complete" while fewer
     * distinct files exist than the customer ordered. On the approval that closes the order, compare the distinct delivered
     * files with the ordered quantities; a shortfall is allowed only with a written reason (at least 10 characters).
     */
    private void requireOrderMediaQuantity(
            com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy.LockedExecution aggregate,
            MissionResult result, MissionResultReviewRequest request) {
        var order = aggregate.order();
        long orderedImages = 0, orderedVideos = 0;
        boolean quantified = false;
        for (var deliverable : order.getDeliverables()) {
            var requirement = deliverable.getRequirement();
            if (requirement == null) continue;
            if (requirement.containsKey("imageQuantity") || requirement.containsKey("videoQuantity")) quantified = true;
            orderedImages += quantity(requirement.get("imageQuantity"));
            orderedVideos += quantity(requirement.get("videoQuantity"));
        }
        if (!quantified) return; // orders created before per-type quantities are not checked
        var effective = aggregate.missions().stream().filter(ExecutionPlanCoverageService::effective).toList();
        boolean lastOpenResult = effective.stream()
                .filter(m -> !m.getId().equals(result.getMission().getId()))
                .allMatch(m -> missionResultRepository.existsByMissionIdAndApprovalStatus(
                        m.getId(), MissionResultApprovalStatus.APPROVED));
        if (!lastOpenResult) return;
        var images = new java.util.HashSet<String>();
        var videos = new java.util.HashSet<String>();
        for (var mission : effective) {
            for (MediaAsset asset : mediaAssetRepository.findByMissionIdOrderByCapturedAtDesc(mission.getId())) {
                if (asset.getMediaStatus() != MediaStatus.AVAILABLE || asset.getOrphanCleanupRequestedAt() != null
                        || asset.getStorageStatus() != com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE) continue;
                String key = asset.getChecksumSha256() == null || asset.getChecksumSha256().isBlank()
                        ? "id:" + asset.getId() : asset.getChecksumSha256().toLowerCase();
                if ("IMAGE".equals(asset.getType())) images.add(key);
                else if ("VIDEO".equals(asset.getType())) videos.add(key);
            }
        }
        if (images.size() >= orderedImages && videos.size() >= orderedVideos) return;
        String note = request == null || request.getNote() == null ? "" : request.getNote().strip();
        if (note.length() >= 10) return;
        throw new ApiException(ErrorCode.ORDER_MEDIA_QUANTITY_NOT_MET,
                "Mới có " + images.size() + "/" + orderedImages + " ảnh và " + videos.size() + "/" + orderedVideos
                        + " video so với đơn đặt. Nhập lý do (ít nhất 10 ký tự) để vẫn duyệt.");
    }

    private static long quantity(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0 : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return 0; }
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionResultResponse reject(String resultId, MissionResultReviewRequest request) {
        MissionResult result = requirePendingResult(resultId);
        Instant now = Instant.now();
        result.setApprovalStatus(MissionResultApprovalStatus.REJECTED);
        result.setRejectedAt(now);
        result.setApprovedAt(null);
        result.setReviewedBy(currentUser.getCurrentUserId());
        result.setReviewNote(request == null ? null : request.getNote());
        return toResponse(missionResultRepository.save(result));
    }

    @Override
    @Transactional
    public void ensureCompletedResult(Mission mission) {
        MissionRuntimeGate.requireFinalizedStructure(mission);
        checklistExecutionService.requireMaterialized(mission);
        missionRepository.findByIdForUpdate(mission.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (missionResultRepository.existsByMissionId(mission.getId())) {
            return;
        }
        MissionResult result = new MissionResult();
        result.setMission(mission);
        result.setStatus(MissionResultStatus.COMPLETED);
        // Operational completion is not business submission. Never gate resource release here.
        result.setApprovalStatus(MissionResultApprovalStatus.DRAFT);
        result.setStartedAt(mission.getActualStartAt());
        result.setEndedAt(mission.getActualEndAt() != null ? mission.getActualEndAt() : mission.getCompletedAt());
        result.setCompletedAt(mission.getCompletedAt() != null ? mission.getCompletedAt() : Instant.now());
        result.setSubmittedAt(null);
        result.setMediaCount(countMedia(mission.getId()));
        attachMissionMedia(result, mission.getId());
        result.setSummary("Mission completed successfully");
        result.setDurationSeconds(resolveDurationSeconds(result.getStartedAt(), result.getEndedAt()));
        missionResultRepository.save(result);
    }

    private void applyRequest(MissionResult result, Mission mission, MissionResultRequest request) {
        Instant submittedAt = Instant.now();
        result.setStatus(request.getStatus() == null ? MissionResultStatus.COMPLETED : request.getStatus());
        result.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        result.setStartedAt(request.getStartedAt() != null ? request.getStartedAt() : mission.getActualStartAt());
        result.setEndedAt(request.getEndedAt() != null ? request.getEndedAt() : mission.getActualEndAt());
        result.setCompletedAt(request.getCompletedAt() != null ? request.getCompletedAt() : submittedAt);
        result.setSubmittedAt(submittedAt);
        result.setApprovedAt(null);
        result.setRejectedAt(null);
        result.setSummary(request.getSummary());
        result.setNotes(request.getNotes());
        result.setReviewedBy(null);
        result.setReviewNote(null);
        result.setMediaCount(countMedia(mission.getId()));
        attachMissionMedia(result, mission.getId());
        result.setDurationSeconds(resolveDurationSeconds(result.getStartedAt(), result.getEndedAt()));
    }

    private MissionResultResponse toResponse(MissionResult result) {
        var viewer = currentUser.getCurrentUser();
        if (viewer != null && viewer.getRole() != null && viewer.getRole().getCode() == RoleCode.CUSTOMER) {
            return missionResultMapper.toResponseWithoutMedia(result);
        }
        String missionId = result.getMission().getId();
        var media = result.getMediaFiles();
        if (media == null || media.isEmpty()) media = mediaAssetRepository.findByMissionIdOrderByCapturedAtDesc(missionId);
        // Response fallback must not dirty a managed result during GET or an approve replay.
        return missionResultMapper.toResponse(result, media, mediaAssetService);
    }

    private void attachMissionMedia(MissionResult result, String missionId) {
        List<MediaAsset> missionMedia = mediaAssetRepository.findByMissionIdOrderByCapturedAtDesc(missionId).stream()
                .filter(asset -> asset.getOrphanCleanupRequestedAt() == null
                        && asset.getStorageStatus() == com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE).toList();
        result.setMediaFiles(new ArrayList<>(missionMedia));
        result.setMediaCount(missionMedia.size());
    }

    private int countMedia(String missionId) {
        List<MediaAsset> media = mediaAssetRepository.findByMissionIdOrderByCapturedAtDesc(missionId);
        return media.size();
    }

    private MissionResult requirePendingResult(String resultId) {
        String missionId = missionResultRepository.findMissionIdByResultId(resultId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        return missionResultRepository.findById(resultId)
                .filter(result -> result.getApprovalStatus() == MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Pending mission result not found: " + resultId));
    }

    private Long resolveDurationSeconds(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            return null;
        }
        return Duration.between(startedAt, endedAt).getSeconds();
    }
}
