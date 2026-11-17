package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionEvidenceSubmissionService {
    private final MissionRepository missions;
    private final MissionEvidenceSubmissionRepository submissions;
    private final MissionChecklistExecutionRepository executions;
    private final IChecklistEvidenceService evidence;
    private final ChecklistEvidencePolicy eligibility;
    private final IMissionChecklistScopeService scope;
    private final IMissionAuthorizationService authorization;
    private final InspectionCorrectionRepository corrections;
    private final EvidenceHandoffPolicy handoff;
    private final AuthenticatedUserResolver users;
    private final Clock clock;

    public record Command(@NotNull UUID clientRequestId, @NotBlank String reviewToken) {}
    public record Revision(long revision, String submittedBy, Instant submittedAt, String correctionId) {}
    public record State(boolean canSubmit, boolean reviewable, String reviewToken, List<Revision> history) {}

    public State get(String missionId) {
        var mission = missions.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!authorization.canViewMission(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        var rows = submissions.findByMission_IdOrderByRevisionDesc(missionId);
        return new State(canSubmit(mission), handoff.reviewable(missionId), token(missionId), rows.stream()
            .map(s -> new Revision(s.getRevision(), s.getSubmittedBy(), s.getSubmittedAt(), s.getCorrectionId())).toList());
    }

    @Transactional
    public State submit(String missionId, Command command) {
        var mission = missions.findByIdForUpdate(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!authorization.canViewMission(missionId)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        // A repeated command is read-only even after the handoff has locked editing.
        if (command != null && command.clientRequestId() != null
                && submissions.findByMission_IdAndCommandId(missionId, command.clientRequestId().toString()).isPresent())
            return get(missionId);
        if (!canSubmit(mission)) throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
        if (command == null || command.clientRequestId() == null
                || !Objects.equals(command.reviewToken(), token(missionId)))
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        MissionRuntimeGate.requireFinalizedStructure(mission);
        scope.requireMaterialized(mission);
        var rows = executions.findOrderedByMissionId(missionId);
        var links = evidence.active(missionId);
        if (rows.isEmpty() || rows.stream().anyMatch(e -> !evidence.ready(e, links, false))
                || links.stream().anyMatch(e -> eligibility.ineligibilityReason(e.getMediaAsset(), false) != null))
            throw new ApiException(ErrorCode.CHECKLIST_NOT_READY,
                "Complete the checklist and validate every attached evidence file before submitting");
        capture(mission, command.clientRequestId().toString(), null);
        return get(missionId);
    }

    private boolean canSubmit(Mission mission) {
        return mission.getStatus() == MissionStatus.IN_PROGRESS && mission.getActualEndAt() != null
            && mission.getCompletedAt() != null && authorization.canAttachChecklistEvidence(mission.getId())
            && !corrections.existsByActiveMissionId(mission.getId()) && !handoff.submitted(mission.getId())
            && handoff.editable(mission.getId());
    }

    /** Caller holds the Mission lock and has validated the correction's readiness. */
    @Transactional
    public void capture(Mission mission, String commandId, String correctionId) {
        if (submissions.findByMission_IdAndCommandId(mission.getId(), commandId).isPresent()) return;
        var row = new MissionEvidenceSubmission();
        row.setMission(mission);
        row.setRevision(submissions.findFirstByMission_IdOrderByRevisionDesc(mission.getId())
            .map(s -> Math.incrementExact(s.getRevision())).orElse(1L));
        row.setCommandId(commandId);
        row.setCorrectionId(correctionId);
        row.setSubmittedBy(users.getCurrentUserId());
        row.setSubmittedAt(Instant.now(clock));
        row.setSnapshot(snapshot(mission.getId()));
        submissions.saveAndFlush(row);
    }

    private List<Map<String, Object>> snapshot(String missionId) {
        var links = evidence.active(missionId);
        return executions.findOrderedByMissionId(missionId).stream().map(e -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("executionId", e.getId());
            value.put("version", e.getVersion());
            value.put("content", e.getOrderChecklistItem().getContent());
            value.put("evidencePolicyVersion", e.getOrderChecklistItem().getEvidencePolicyVersion());
            value.put("minimumEvidenceCount", e.getOrderChecklistItem().getMinimumEvidenceCount());
            value.put("minimumImageCount", e.getOrderChecklistItem().getMinimumImageCount());
            value.put("minimumVideoCount", e.getOrderChecklistItem().getMinimumVideoCount());
            value.put("executionStatus", e.getExecutionStatus().name());
            value.put("observation", e.getObservation());
            value.put("unableToVerifyReason", e.getUnableToVerifyReason());
            value.put("assessmentStatus", e.getAssessmentStatus().name());
            value.put("assessmentNote", e.getAssessmentNote());
            value.put("evidence", links.stream().filter(l -> e.getId().equals(l.getMissionChecklistExecution().getId()))
                .sorted(Comparator.comparing(MissionChecklistEvidence::getId)).map(l -> {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("evidenceId", l.getId()); file.put("mediaId", l.getMediaAsset().getId());
                    file.put("checksumSha256", l.getMediaAsset().getChecksumSha256());
                    return file;
                }).toList());
            return value;
        }).toList();
    }

    private String token(String missionId) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(snapshot(missionId).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
