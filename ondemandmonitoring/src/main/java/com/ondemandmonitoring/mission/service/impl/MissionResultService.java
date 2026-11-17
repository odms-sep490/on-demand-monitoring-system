package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.media.domain.MediaAsset;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired
    @lombok.experimental.NonFinal
    IDeliveryWorkflowService deliveryWorkflow;
    @Autowired @lombok.experimental.NonFinal
    com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy inspectionCorrections;

    @Autowired @lombok.experimental.NonFinal
    EvidenceHandoffPolicy evidenceHandoff;

    @Autowired @lombok.experimental.NonFinal
    com.ondemandmonitoring.mission.inspection.service.IInspectionCorrectionService correctionWorkflow;

    @Autowired @org.springframework.context.annotation.Lazy @lombok.experimental.NonFinal
    com.ondemandmonitoring.mission.service.IMissionService missionLifecycle;

    @Override
    @Transactional
    public void completeSupplementedSource(Mission child) {
        var correction = child.getInspectionCorrection();
        if (correction == null) throw new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING);
        var accepted = inspectionCorrections.acceptedSupplement(correction);
        if (!child.getId().equals(accepted.getId())
                || correction.getStatus() != com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus.RESOLVED)
            throw new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING);
        var source = correction.getMission();
        var aggregate = completionPolicy.lockAggregate(source.getOrder().getId());
        var result = missionResultRepository.findByMissionId(source.getId()).orElse(null);
        if (result != null && result.getApprovalStatus() == MissionResultApprovalStatus.APPROVED) {
            deliveryWorkflow.publishInspectedResults(source.getOrder().getId());
            return;
        }
        if (source.getStatus() != MissionStatus.IN_PROGRESS || source.getActualEndAt() == null
                || source.getFailureRequestedAt() != null || inspectionCorrections.hasOpen(source.getId())
                || !checklistExecutionService.isReadyForInspectorApproval(source)) return;
        missionLifecycle.completeMissionAfterSupplement(child);
        ensureCompletedResult(source);
        result = missionResultRepository.findByMissionId(source.getId()).orElseThrow();
        var childResult = missionResultRepository.findByMissionId(child.getId()).orElseThrow();
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        result.setInspectorConfirmed(true);
        result.setApprovedAt(Instant.now());
        result.setSubmittedAt(Instant.now());
        result.setReviewedBy(childResult.getReviewedBy());
        result.setReviewNote("Completed using accepted supplemental mission " + child.getMissionCode());
        result.setStatus(MissionResultStatus.COMPLETED);
        source.setStatus(MissionStatus.COMPLETED);
        missionRepository.saveAndFlush(source);
        missionResultRepository.saveAndFlush(result);
        completionPolicy.completeIfReady(aggregate);
        deliveryWorkflow.publishInspectedResults(source.getOrder().getId());
    }

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
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        var aggregate = completionPolicy.lockAggregate(orderId);
        Mission mission = aggregate.missions().stream().filter(row -> missionId.equals(row.getId())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        MissionRuntimeGate.requireFinalizedStructure(mission);
        checklistExecutionService.requireMaterialized(mission);
        if (!authorization.canSubmitMissionResult(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        MissionResult result = missionResultRepository.findByMissionId(missionId).orElse(null);
        // A retry after confirmation must not overwrite the frozen inspection package.
        if (result != null && result.getApprovalStatus() == MissionResultApprovalStatus.APPROVED) {
            if (mission.getInspectionCorrection() != null) correctionWorkflow.registerAcceptedSupplement(mission);
            deliveryWorkflow.publishInspectedResults(orderId);
            return toResponse(result);
        }
        if (mission.getStatus() != MissionStatus.IN_PROGRESS || mission.getActualEndAt() == null
                || mission.getCompletedAt() == null)
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Complete postflight acceptance before confirming the inspection package");
        if (request == null || request.getStatus() == MissionResultStatus.FAILED)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        evidenceHandoff.requireReviewable(missionId);
        inspectionCorrections.requireResolved(missionId);
        checklistExecutionService.requireReadyForInspectorApproval(mission);
        if (result == null) {
            result = new MissionResult();
            result.setMission(mission);
            result.setCreatedBy(currentUser.getCurrentUserId());
        }
        applyRequest(result, mission, request);
        Instant now = Instant.now();
        result.setStatus(MissionResultStatus.COMPLETED);
        result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        result.setApprovedAt(now);
        result.setReviewedBy(currentUser.getCurrentUserId());
        result.setInspectorConfirmed(true);
        // Detailed inspection reports stay in notes; review_note is a bounded reviewer decision.
        mission.setStatus(MissionStatus.COMPLETED);
        missionRepository.saveAndFlush(mission);
        var saved = missionResultRepository.saveAndFlush(result);
        if (mission.getInspectionCorrection() != null) correctionWorkflow.registerAcceptedSupplement(mission);
        completionPolicy.completeIfReady(aggregate);
        deliveryWorkflow.publishInspectedResults(orderId);
        return toResponse(saved);
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
    // Retired manager review route. Inspection is confirmed and published through upsert.
    @Deprecated
    @PreAuthorize("denyAll()")
    public MissionResultResponse approve(String resultId, MissionResultReviewRequest request) {
        throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    @Override
    @Transactional
    // Retired manager review route. Inspection is confirmed and published through upsert.
    @Deprecated
    @PreAuthorize("denyAll()")
    public MissionResultResponse reject(String resultId, MissionResultReviewRequest request) {
        throw new ApiException(ErrorCode.ACCESS_DENIED);
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

    private Long resolveDurationSeconds(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            return null;
        }
        return Duration.between(startedAt, endedAt).getSeconds();
    }
}
