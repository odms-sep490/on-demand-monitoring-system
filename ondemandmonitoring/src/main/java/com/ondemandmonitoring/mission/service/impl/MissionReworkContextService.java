package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.ResultRevisionRequestItem;
import com.ondemandmonitoring.mission.dto.response.MissionReworkContextResponse;
import com.ondemandmonitoring.delivery.repository.ResultRevisionRequestItemMediaRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionRequestItemRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionReworkChecklistContextRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissionReworkContextService implements com.ondemandmonitoring.mission.service.IMissionReworkContextService {
    private final MissionRepository missions;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final MissionReworkChecklistContextRepository contexts;
    private final ResultRevisionRequestItemRepository requestItems;
    private final ResultRevisionRequestItemMediaRepository requestMedia;
    private final IMissionAuthorizationService authorization;
    private final AuthenticatedUserResolver currentUser;
    private final IResultRetentionAccessService retentionAccess;
    private final IMediaObjectStorage storage;

    @Override
    @Transactional(readOnly = true)
    public MissionReworkContextResponse get(String missionId) {
        var mission = missions.findByIdWithOrder(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (mission.getPurpose() != MissionPurpose.REWORK || mission.getResultRevisionCaseId() == null)
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        var user = currentUser.getCurrentUser();
        boolean manager = authorization.canManageMissions();
        boolean assignedStaff = user.getRole() != null && user.getRole().getCode() == RoleCode.STAFF
                && authorization.canViewMission(missionId)
                && staffAssignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(
                        missionId, user.getId()).stream().anyMatch(assignment -> assignment.getReleasedAt() == null
                                && assignment.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED);
        if (!manager && !assignedStaff) throw new ApiException(ErrorCode.ACCESS_DENIED);

        var rows = contexts.findByMissionIdOrderByCreatedAtAsc(missionId);
        List<MissionReworkContextResponse.Checklist> result = new ArrayList<>();
        for (var snapshot : rows) {
            var item = snapshot.getCaseItem();
            List<ResultRevisionRequestItem> loadedFeedback = requestItems.findAllById(snapshot.getSourceFeedbackItemIds())
                    .stream().toList();
            var feedbackById = loadedFeedback.stream().collect(java.util.stream.Collectors.toMap(
                    ResultRevisionRequestItem::getId, row -> row));
            List<ResultRevisionRequestItem> feedbackRows = snapshot.getSourceFeedbackItemIds().stream()
                    .map(feedbackById::get).filter(java.util.Objects::nonNull).toList();
            List<String> feedbackItemIds = feedbackRows.stream().map(ResultRevisionRequestItem::getId).toList();
            var mediaByFeedback = feedbackItemIds.isEmpty() ? java.util.Map.<String, List<com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence>>of()
                    : requestMedia.findByRequestItemIdIn(feedbackItemIds).stream()
                            .collect(java.util.stream.Collectors.groupingBy(
                                    link -> link.getRequestItem().getId(),
                                    java.util.stream.Collectors.mapping(
                                            com.ondemandmonitoring.delivery.domain.ResultRevisionRequestItemMedia::getRoundEvidence,
                                            java.util.stream.Collectors.toList())));
            var feedback = feedbackRows.stream().map(row -> new MissionReworkContextResponse.Feedback(
                    row.getRequest().getRound().getRoundNumber(), row.getReasonCode(), row.getNote(),
                    row.getRequest().getSubmittedAt(), mediaByFeedback.getOrDefault(row.getId(), List.of()).stream()
                            .map(this::mapEvidence).toList())).toList();
            var guidance = snapshot.getGuidanceSnapshot();
            result.add(new MissionReworkContextResponse.Checklist(item.getId(),
                    snapshot.getOrderChecklistItemIdSnapshot(), snapshot.getChecklistTitleSnapshot(),
                    snapshot.getManagerInstructionsSnapshot(), guidance == null ? null
                            : new MissionReworkContextResponse.Guidance(text(guidance, "description"),
                                    strings(guidance, "executionSteps"), strings(guidance, "inputRequirements"),
                                    strings(guidance, "expectedOutputs")), feedback));
        }
        return new MissionReworkContextResponse(mission.getId(), mission.getMissionCode(),
                mission.getResultRevisionCaseId(), result);
    }

    private MissionReworkContextResponse.Evidence mapEvidence(
            com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence evidence) {
        var asset = evidence.getMediaAsset();
        try {
            retentionAccess.requireAccessible(asset);
            return new MissionReworkContextResponse.Evidence(evidence.getId(), asset.getId(),
                    evidence.getFileNameSnapshot(), evidence.getMediaTypeSnapshot(),
                    storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key()),
                    Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds()), null);
        } catch (ApiException ex) {
            if (ex.getErrorCode() != ErrorCode.RESULT_RETENTION_EXPIRED && ex.getErrorCode() != ErrorCode.MEDIA_NOT_FOUND)
                throw ex;
            return new MissionReworkContextResponse.Evidence(evidence.getId(), asset.getId(),
                    evidence.getFileNameSnapshot(), evidence.getMediaTypeSnapshot(), null, null,
                    ex.getErrorCode() == ErrorCode.RESULT_RETENTION_EXPIRED ? "RETENTION_EXPIRED" : "MEDIA_UNAVAILABLE");
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode value, String key) {
        return value == null || value.get(key) == null || value.get(key).isNull() ? null : value.get(key).asText();
    }

    private static List<String> strings(com.fasterxml.jackson.databind.JsonNode value, String key) {
        if (value == null || !value.path(key).isArray()) return List.of();
        List<String> rows = new ArrayList<>();
        value.path(key).forEach(entry -> rows.add(entry.asText()));
        return rows;
    }
}
