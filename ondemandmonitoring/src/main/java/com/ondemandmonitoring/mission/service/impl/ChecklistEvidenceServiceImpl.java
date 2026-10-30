package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.ChecklistEvidenceMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistEvidenceServiceImpl implements IChecklistEvidenceService {
    private final MissionRepository missions;
    private final MissionChecklistExecutionRepository executions;
    private final MissionChecklistEvidenceRepository evidence;
    private final MediaAssetRepository media;
    private final MissionResultRepository results;
    private final IMissionAuthorizationService authorization;
    private final AuthenticatedUserResolver currentUser;
    private final ChecklistEvidencePolicy policy;
    private final ChecklistEvidenceMapper mapper;
    private final IMediaObjectStorage storage;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ondemandmonitoring.media.service.IMediaOrphanCleanupService orphanCleanup;

    @Override
    @Transactional
    public List<ChecklistEvidenceResponse> attach(String missionId, BatchChecklistEvidenceRequest request) {
        Mission mission = mission(missionId, true);
        if (!authorization.canViewMission(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        editable(missionId);
        if (!authorization.canAttachChecklistEvidence(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (request == null || request.mediaId() == null || request.mediaId().isBlank() || request.targets() == null
                || request.targets().isEmpty() || request.targets().size() > 100
                || request.targets().stream().anyMatch(t -> t == null || t.executionId() == null || t.executionId().isBlank()
                        || t.expectedVersion() == null || t.expectedVersion() < 0))
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        MediaAsset asset = media.findByIdForUpdate(request.mediaId()).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        if (!missionId.equals(asset.getMissionId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (!policy.attachable(asset)) throw new ApiException(ErrorCode.EVIDENCE_NOT_ELIGIBLE, policy.ineligibilityReason(asset, false));
        if (orphanCleanup != null) orphanCleanup.cancel(asset.getId());
        var response = new ArrayList<ChecklistEvidenceResponse>();
        var seen = new HashSet<String>();
        // Mission lock serializes all same-mission commands; the SQL partial index remains the final boundary.
        for (var target : request.targets().stream().sorted(Comparator.comparing(BatchChecklistEvidenceRequest.Target::executionId)).toList()) {
            if (!seen.add(target.executionId())) throw new ApiException(ErrorCode.INVALID_REQUEST, "Duplicate execution target");
            MissionChecklistExecution execution = execution(mission, target.executionId());
            var requiredType = execution.getOrderChecklistItem().getEvidencePolicyVersion() == 1
                    ? RequiredEvidenceType.IMAGE_OR_VIDEO : execution.getOrderChecklistItem().getRequiredEvidenceType();
            if (!policy.mediaTypeMatches(requiredType, asset.getType())) {
                throw new ApiException(ErrorCode.EVIDENCE_NOT_ELIGIBLE,
                        "Checklist item '" + execution.getOrderChecklistItem().getContent() + "' requires " + requiredType
                                + " evidence; media type " + asset.getType() + " does not qualify");
            }
            var existing = evidence.findByMissionChecklistExecution_IdAndMediaAsset_IdAndDetachedAtIsNull(execution.getId(), asset.getId());
            if (existing.isPresent()) { response.add(toResponse(existing.get())); continue; }
            version(execution, target.expectedVersion());
            var link = new MissionChecklistEvidence();
            link.setMissionId(missionId); link.setMissionChecklistExecution(execution); link.setMediaAsset(asset);
            link.setAttachedBy(currentUser.getCurrentUserId()); link.setAttachedAt(Instant.now());
            link.setNote(trimmed(request.note()));
            response.add(toResponse(evidence.saveAndFlush(link)));
            touch(execution);
        }
        return response;
    }

    @Override
    @Transactional
    public void detach(String missionId, String executionId, String evidenceId, long expectedVersion, String reason) {
        Mission mission = mission(missionId, true); editable(missionId);
        if (!authorization.canViewMission(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        if (!authorization.canDetachChecklistEvidence(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        MissionChecklistExecution execution = execution(mission, executionId);
        var link = evidence.findById(evidenceId).orElseThrow(() -> new ApiException(ErrorCode.EVIDENCE_NOT_FOUND));
        if (!missionId.equals(link.getMissionId()) || !executionId.equals(link.getMissionChecklistExecution().getId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        media.findByIdForUpdate(link.getMediaAsset().getId()).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        if (link.getDetachedAt() != null) return;
        version(execution, expectedVersion);
        link.setDetachedBy(currentUser.getCurrentUserId()); link.setDetachedAt(Instant.now());
        link.setDetachReason(trimmed(reason)); evidence.saveAndFlush(link); touch(execution);
        if (orphanCleanup != null) orphanCleanup.request(link.getMediaAsset().getId());
    }

    @Override
    public List<ChecklistEvidenceCandidate> candidates(String missionId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ApiException(ErrorCode.INVALID_REQUEST);
        mission(missionId, false);
        if (!authorization.canViewMission(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        var links = active(missionId);
        var executionTypes = links.stream().filter(link -> link.getDetachedAt() == null)
                .collect(java.util.stream.Collectors.toMap(
                        link -> link.getMissionChecklistExecution().getId(),
                        link -> {
                            var item = link.getMissionChecklistExecution().getOrderChecklistItem();
                            return item.getEvidencePolicyVersion() == 1 ? RequiredEvidenceType.IMAGE_OR_VIDEO : item.getRequiredEvidenceType();
                        }, (left, right) -> left));
        return media.findMissionCandidates(missionId, PageRequest.of(page, size)).stream().map(asset -> {
            String url = preview(asset);
            String reason = policy.ineligibilityReason(asset, false);
            var attachedExecutionIds = links.stream().filter(e -> asset.getId().equals(e.getMediaAsset().getId())
                    && e.getDetachedAt() == null).map(e -> e.getMissionChecklistExecution().getId()).distinct().toList();
            var wrongTypeForAnyExisting = attachedExecutionIds.stream()
                    .map(executionTypes::get).filter(Objects::nonNull)
                    .anyMatch(type -> !policy.mediaTypeMatches(type, asset.getType()));
            if (reason == null && wrongTypeForAnyExisting) reason = "EVIDENCE_TYPE_NOT_ELIGIBLE";
            return new ChecklistEvidenceCandidate(asset.getId(), asset.getOriginalFileName(), asset.getType(), asset.getContentType(),
                    asset.getMediaStatus(), asset.getSourceType(), asset.getCapturedAt(), asset.getValidatedAt(), policy.attachable(asset),
                    reason == null, reason == null && policy.ineligibilityReason(asset, true) == null,
                    reason, url, url == null ? null : Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()), attachedExecutionIds);
        }).toList();
    }

    @Override
    public List<MissionChecklistEvidence> active(String missionId) {
        var links = evidence.findActiveByMissionId(missionId);
        if (links.stream().anyMatch(link -> !missionId.equals(link.getMissionChecklistExecution().getMissionId())
                || !missionId.equals(link.getMediaAsset().getMissionId())))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        return links;
    }

    @Override
    public boolean ready(MissionChecklistExecution execution, List<MissionChecklistEvidence> links, boolean finalApproval) {
        if (execution.getExecutionStatus() == ChecklistExecutionStatus.UNABLE_TO_VERIFY)
            return execution.getUnableToVerifyReason() != null && !execution.getUnableToVerifyReason().isBlank();
        if (execution.getExecutionStatus() != ChecklistExecutionStatus.COMPLETED) return false;
        return evidenceRequirementsSatisfied(execution, links, finalApproval);
    }

    @Override
    public boolean evidenceRequirementsSatisfied(MissionChecklistExecution execution, List<MissionChecklistEvidence> links, boolean finalApproval) {
        var item = execution.getOrderChecklistItem();
        if (item.getEvidencePolicyVersion() == 0) return item.getMinimumEvidenceCount() == 0;
        if (item.getEvidencePolicyVersion() == 3) return v3Satisfied(item, execution, links, finalApproval);
        if (item.getEvidencePolicyVersion() == 1) {
            if (item.getMinimumEvidenceCount() != 1) return false;
        } else if (item.getEvidencePolicyVersion() != 2 || item.getMinimumEvidenceCount() < 1
                || item.getMinimumEvidenceCount() > 50 || item.getRequiredEvidenceType() == null) {
            return false;
        }
        RequiredEvidenceType requiredType = item.getEvidencePolicyVersion() == 1
                ? RequiredEvidenceType.IMAGE_OR_VIDEO : item.getRequiredEvidenceType();
        var applicable = links.stream().filter(e -> e.getDetachedAt() == null && execution.getId().equals(e.getMissionChecklistExecution().getId())).toList();
        long count = policy.eligibleDistinctCount(applicable, execution.getId(), requiredType, finalApproval);
        // Required packages cannot retain rejected, pending, invalid-type, or otherwise ineligible extra evidence at final approval.
        return count >= item.getMinimumEvidenceCount() && (!finalApproval || applicable.stream().allMatch(e ->
                policy.mediaTypeMatches(requiredType, e.getMediaAsset().getType())
                        && policy.ineligibilityReason(e.getMediaAsset(), true) == null));
    }

    /**
     * Policy V3 needs the requested images AND the requested videos. A pile of one type never stands in for the other, and at
     * final approval every attached asset must be eligible.
     */
    private boolean v3Satisfied(OrderChecklistItem item, MissionChecklistExecution execution, List<MissionChecklistEvidence> links, boolean finalApproval) {
        if (item.getMinimumImageCount() < 0 || item.getMinimumVideoCount() < 0
                || item.getMinimumImageCount() + item.getMinimumVideoCount() < 1
                || item.getMinimumEvidenceCount() != item.getMinimumImageCount() + item.getMinimumVideoCount()) return false;
        var applicable = links.stream().filter(e -> e.getDetachedAt() == null && execution.getId().equals(e.getMissionChecklistExecution().getId())).toList();
        long images = policy.eligibleDistinctCount(applicable, execution.getId(), RequiredEvidenceType.IMAGE, finalApproval);
        long videos = policy.eligibleDistinctCount(applicable, execution.getId(), RequiredEvidenceType.VIDEO, finalApproval);
        return images >= item.getMinimumImageCount() && videos >= item.getMinimumVideoCount()
                && (!finalApproval || applicable.stream().allMatch(e -> policy.ineligibilityReason(e.getMediaAsset(), true) == null));
    }

    @Override
    public String readinessFailure(MissionChecklistExecution execution, List<MissionChecklistEvidence> links, boolean finalApproval) {
        if (ready(execution, links, finalApproval) || execution.getExecutionStatus() == ChecklistExecutionStatus.UNABLE_TO_VERIFY) return null;
        var item = execution.getOrderChecklistItem();
        if (item.getEvidencePolicyVersion() == 3) {
            long images = policy.eligibleDistinctCount(links, execution.getId(), RequiredEvidenceType.IMAGE, finalApproval);
            long videos = policy.eligibleDistinctCount(links, execution.getId(), RequiredEvidenceType.VIDEO, finalApproval);
            var reasons = new LinkedHashSet<String>();
            if (execution.getExecutionStatus() != ChecklistExecutionStatus.COMPLETED) reasons.add("EXECUTION_NOT_COMPLETED");
            links.stream().filter(link -> link.getDetachedAt() == null && execution.getId().equals(link.getMissionChecklistExecution().getId()))
                    .map(link -> policy.ineligibilityReason(link.getMediaAsset(), finalApproval)).filter(Objects::nonNull).forEach(reasons::add);
            if (images < item.getMinimumImageCount() || videos < item.getMinimumVideoCount()) reasons.add("INSUFFICIENT_EVIDENCE");
            return "Checklist item '" + item.getContent() + "' requires at least " + item.getMinimumImageCount() + " image(s) and "
                    + item.getMinimumVideoCount() + " video(s); current eligible counts are " + images + " image(s) and " + videos
                    + " video(s). Blockers: " + (reasons.isEmpty() ? "INSUFFICIENT_EVIDENCE" : String.join(", ", reasons));
        }
        if (item.getEvidencePolicyVersion() != 0 && item.getEvidencePolicyVersion() != 1 && item.getEvidencePolicyVersion() != 2) {
            return "Checklist item '" + item.getContent() + "' has unsupported evidence policy version " + item.getEvidencePolicyVersion() + ".";
        }
        var type = item.getEvidencePolicyVersion() == 1 ? RequiredEvidenceType.IMAGE_OR_VIDEO : item.getRequiredEvidenceType();
        if (item.getEvidencePolicyVersion() == 2 && type == null) {
            return "Checklist item '" + item.getContent() + "' has no supported required evidence type.";
        }
        int count = policy.eligibleDistinctCount(links, execution.getId(), type, finalApproval);
        var applicable = links.stream().filter(link -> link.getDetachedAt() == null
                && execution.getId().equals(link.getMissionChecklistExecution().getId())).toList();
        var reasons = new LinkedHashSet<String>();
        if (execution.getExecutionStatus() != ChecklistExecutionStatus.COMPLETED) reasons.add("EXECUTION_NOT_COMPLETED");
        applicable.stream().map(link -> {
            if (!policy.mediaTypeMatches(type, link.getMediaAsset().getType())) return "EVIDENCE_TYPE_NOT_ELIGIBLE";
            return policy.ineligibilityReason(link.getMediaAsset(), finalApproval);
        }).filter(Objects::nonNull).forEach(reasons::add);
        if (count < item.getMinimumEvidenceCount()) reasons.add("INSUFFICIENT_EVIDENCE");
        String labels = reasons.isEmpty() ? "INSUFFICIENT_EVIDENCE" : String.join(", ", reasons);
        return "Checklist item '" + item.getContent() + "' requires at least " + item.getMinimumEvidenceCount()
                + " " + (type == null ? "unknown media type" : type.name()) + " evidence asset(s); current eligible count is "
                + count + ". Blockers: " + labels;
    }

    @Override
    public ChecklistExecutionResponse enrich(ChecklistExecutionResponse response, MissionChecklistExecution execution, List<MissionChecklistEvidence> links) {
        var applicable = links.stream().filter(e -> execution.getId().equals(e.getMissionChecklistExecution().getId()) && e.getDetachedAt() == null).toList();
        response.setEvidencePolicyVersion(execution.getOrderChecklistItem().getEvidencePolicyVersion());
        response.setMinimumEvidenceCount(execution.getOrderChecklistItem().getMinimumEvidenceCount());
        response.setRequiredEvidenceType(execution.getOrderChecklistItem().getEvidencePolicyVersion() == 1
                ? RequiredEvidenceType.IMAGE_OR_VIDEO : execution.getOrderChecklistItem().getRequiredEvidenceType());
        response.setEvidence(applicable.stream().map(this::toResponse).toList());
        response.setEligibleEvidenceCount(policy.eligibleDistinctCount(applicable, execution.getId(),
                response.getRequiredEvidenceType(), false));
        var enrichedItem = execution.getOrderChecklistItem();
        boolean v3 = enrichedItem.getEvidencePolicyVersion() == 3;
        if (v3) {
            response.setMinimumImageCount(enrichedItem.getMinimumImageCount());
            response.setMinimumVideoCount(enrichedItem.getMinimumVideoCount());
            response.setEligibleImageCount((int) policy.eligibleDistinctCount(applicable, execution.getId(), RequiredEvidenceType.IMAGE, false));
            response.setEligibleVideoCount((int) policy.eligibleDistinctCount(applicable, execution.getId(), RequiredEvidenceType.VIDEO, false));
            response.setEligibleEvidenceCount(response.getEligibleImageCount() + response.getEligibleVideoCount());
        }
        response.setEvidenceRequirementSatisfied(execution.getExecutionStatus() == ChecklistExecutionStatus.UNABLE_TO_VERIFY
                || (v3 ? response.getEligibleImageCount() >= response.getMinimumImageCount()
                        && response.getEligibleVideoCount() >= response.getMinimumVideoCount()
                : response.getEligibleEvidenceCount() >= response.getMinimumEvidenceCount()));
        response.setEvidenceReady(ready(execution, links, false));
        var blockers = new LinkedHashSet<String>();
        if (!execution.getExecutionStatus().isTerminal()) blockers.add("EXECUTION_NOT_TERMINAL");
        if (!response.isEvidenceRequirementSatisfied()) {
            blockers.add("INSUFFICIENT_EVIDENCE");
            applicable.stream().map(link -> {
                if (!policy.mediaTypeMatches(response.getRequiredEvidenceType(), link.getMediaAsset().getType())) return "EVIDENCE_TYPE_NOT_ELIGIBLE";
                return policy.ineligibilityReason(link.getMediaAsset(), false);
            }).filter(Objects::nonNull).forEach(blockers::add);
        }
        if (execution.getExecutionStatus() == ChecklistExecutionStatus.UNABLE_TO_VERIFY && !response.isEvidenceReady()) blockers.add("UNABLE_REASON_REQUIRED");
        response.setBlockingReasons(List.copyOf(blockers)); return response;
    }

    private ChecklistEvidenceResponse toResponse(MissionChecklistEvidence link) {
        var response = mapper.toResponse(link); var asset = link.getMediaAsset();
        var item = link.getMissionChecklistExecution().getOrderChecklistItem();
        RequiredEvidenceType requiredType = item.getEvidencePolicyVersion() == 1
                ? RequiredEvidenceType.IMAGE_OR_VIDEO : item.getRequiredEvidenceType();
        String typeFailure = policy.mediaTypeMatches(requiredType, asset.getType()) ? null : "EVIDENCE_TYPE_NOT_ELIGIBLE";
        String operationalFailure = typeFailure != null ? typeFailure : policy.ineligibilityReason(asset, false);
        String finalFailure = typeFailure != null ? typeFailure : policy.ineligibilityReason(asset, true);
        response.setEligibleForOperationalReadiness(operationalFailure == null);
        response.setEligibleForFinalApproval(finalFailure == null);
        response.setIneligibilityReason(operationalFailure);
        response.setPreviewUrl(preview(asset));
        if (response.getPreviewUrl() != null) response.setUrlExpiresAt(Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()));
        return response;
    }
    private String preview(MediaAsset asset) {
        // Never sign upload-pending/nonvalidated objects for evidence previews.
        if (asset.getStorageStatus() != com.ondemandmonitoring.media.enums.MediaStorageStatus.AVAILABLE || asset.getValidatedAt() == null || asset.getMediaStatus() == null
                || !Set.of(MediaStatus.PENDING_MANAGER_APPROVAL, MediaStatus.AVAILABLE, MediaStatus.REJECTED).contains(asset.getMediaStatus())) return null;
        return storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key());
    }
    private Mission mission(String id, boolean lock) {
        return (lock ? missions.findByIdForUpdate(id) : missions.findById(id)).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    }
    private MissionChecklistExecution execution(Mission mission, String id) {
        var execution = executions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_EXECUTION_NOT_FOUND));
        if (!mission.getId().equals(execution.getMissionId())) throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_PARENT_MISMATCH);
        MissionRuntimeGate.requireFinalizedStructure(mission);
        if (!ChecklistAssignmentIntegrity.matches(mission, execution))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        if (!mission.getOrder().getId().equals(execution.getOrderId()) || execution.getOrderChecklistItem() == null
                || !mission.getOrder().getId().equals(execution.getOrderChecklistItem().getOrder().getId()))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        return execution;
    }
    private void editable(String missionId) {
        if (results.findByMissionId(missionId).map(r -> r.getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                || r.getApprovalStatus() == MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL).orElse(false))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
    }
    private void version(MissionChecklistExecution execution, Long expected) {
        if (expected == null || expected < 0 || !Objects.equals(expected, execution.getVersion())) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
    }
    private void touch(MissionChecklistExecution execution) {
        execution.setUpdatedAt(Instant.now()); execution.setLastModifiedBy(currentUser.getCurrentUserId()); executions.saveAndFlush(execution);
    }
    private String trimmed(String value) {
        if (value == null) return null;
        if (value.length() > 1000) throw new ApiException(ErrorCode.INVALID_REQUEST);
        return value.isBlank() ? null : value.strip();
    }
}
