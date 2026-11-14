package com.ondemandmonitoring.mission.inspection.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionEvent;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionItem;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionDecision;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionRequest;
import com.ondemandmonitoring.mission.inspection.dto.response.InspectionCorrectionResponse;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.mapper.InspectionCorrectionMapper;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionEventRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.inspection.service.IInspectionCorrectionService;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.MissionRuntimeGate;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionCorrectionService implements IInspectionCorrectionService {
  private final MissionRepository missions;
  private final MissionResultRepository results;
  private final MissionChecklistExecutionRepository executions;
  private final MissionChecklistEvidenceRepository evidence;
  private final InspectionCorrectionRepository corrections;
  private final InspectionCorrectionItemRepository items;
  private final InspectionCorrectionEventRepository events;
  private final IMissionAuthorizationService authorization;
  private final AuthenticatedUserResolver users;
  private final IMissionChecklistScopeService scope;
  private final IChecklistEvidenceService evidencePolicy;
  private final InspectionCorrectionMapper mapper;
  private final Clock clock;
  private final com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy aggregate;
  private final InspectionCorrectionPolicy correctionPolicy;

  @Override
  public List<InspectionCorrectionResponse> list(String missionId) {
    missions.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    if (!authorization.canViewMission(missionId)) throw denied();
    return corrections.findByMission_IdOrderByRequestedAtDescIdDesc(missionId).stream()
        .map(this::response)
        .toList();
  }

  @Override
  @Transactional
  public InspectionCorrectionResponse request(
      String missionId, InspectionCorrectionRequest request) {
    Mission mission = lock(missionId);
    if (!authorization.canAssessMonitoringChecklist(missionId)) throw denied();
    editable(mission);
    if (request == null
        || request.clientRequestId() == null
        || request.items() == null
        || request.items().isEmpty()
        || request.items().size() > 100) throw invalid();
    var requested = new TreeMap<String, InspectionCorrectionRequest.Item>();
    for (var item : request.items()) {
      if (item == null
          || item.executionId() == null
          || item.expectedVersion() == null
          || item.expectedVersion() < 0
          || item.reason() == null
          || item.reason().isBlank()
          || item.reason().length() > 2000
          || item.mediaIds() == null
          || item.mediaIds().size() > 100
          || item.mediaIds().stream().anyMatch(id -> id == null || id.isBlank())
          || new HashSet<>(item.mediaIds()).size() != item.mediaIds().size()
          || requested.put(item.executionId(), item) != null) throw invalid();
    }
    var replay =
        corrections.findByMission_IdAndClientRequestId(
            missionId, request.clientRequestId().toString());
    if (replay.isPresent()) {
      var saved = items.findItems(replay.get().getId());
      if (saved.size() != requested.size()
          || saved.stream()
              .anyMatch(
                  i -> {
                    var r = requested.get(i.getExecution().getId());
                    return r == null
                        || r.expectedVersion() != i.getRequestedExecutionVersion()
                        || !r.reason().trim().equals(i.getReason())
                        || !new TreeSet<>(r.mediaIds()).equals(new TreeSet<>(i.getMediaIds()));
                  })) throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
      return response(replay.get());
    }
    if (corrections.existsByActiveMissionId(missionId)) throw conflict();
    var actual =
        executions.findOrderedByMissionId(missionId).stream()
            .collect(java.util.stream.Collectors.toMap(MissionChecklistExecution::getId, e -> e));
    var links = evidence.findActiveByMissionId(missionId);
    var frozen = correctionPolicy.delegatedExecutions(missionId);
    for (var entry : requested.values()) {
      var execution = actual.get(entry.executionId());
      if (frozen.containsKey(entry.executionId()))
        throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
      if (execution == null
          || !missionId.equals(execution.getMissionId())
          || !mission.getOrder().getId().equals(execution.getOrderId()))
        throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_PARENT_MISMATCH);
      if (!entry.expectedVersion().equals(execution.getVersion()))
        throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
      var mediaIds =
          links.stream()
              .filter(e -> e.getMissionChecklistExecution().getId().equals(execution.getId()))
              .map(e -> e.getMediaAsset().getId())
              .collect(java.util.stream.Collectors.toSet());
      if (!mediaIds.containsAll(entry.mediaIds()))
        throw new ApiException(ErrorCode.EVIDENCE_NOT_FOUND);
    }
    var correction = new InspectionCorrection();
    correction.setMission(mission);
    correction.setActiveMissionId(missionId);
    correction.setClientRequestId(request.clientRequestId().toString());
    correction.setStatus(InspectionCorrectionStatus.OPEN);
    correction.setRequestedAt(Instant.now(clock));
    correction.setRequestedBy(users.getCurrentUserId());
    corrections.saveAndFlush(correction);
    for (var entry : requested.values()) {
      var row = new InspectionCorrectionItem();
      row.setCorrection(correction);
      row.setExecution(actual.get(entry.executionId()));
      row.setRequestedExecutionVersion(entry.expectedVersion());
      row.setContentSnapshot(row.getExecution().getOrderChecklistItem().getContent());
      row.setReason(entry.reason().trim());
      row.setMediaIds(List.copyOf(new TreeSet<>(entry.mediaIds())));
      items.save(row);
    }
    items.flush();
    event(correction, "REQUESTED", null);
    return response(correction);
  }

  @Override
  @Transactional
  public InspectionCorrectionResponse resubmit(
      String missionId, String id, InspectionCorrectionDecision decision) {
    var mission = lock(missionId);
    if (!authorization.canAttachChecklistEvidence(missionId)) throw denied();
    var correction = required(missionId, id);
    editable(mission);
    version(correction, decision);
    if (correction.getStatus() != InspectionCorrectionStatus.OPEN) throw conflict();
    // Do not change assessments: only the assigned Inspector can accept the repaired work.
    if (correction.getSupplementalMission() != null)
      correctionPolicy.acceptedSupplement(correction);
    else
      for (var item : items.findItems(id))
        if (!evidencePolicy.ready(item.getExecution(), evidencePolicy.active(missionId), false))
          throw new ApiException(ErrorCode.CHECKLIST_NOT_READY);
    correction.setStatus(InspectionCorrectionStatus.SUBMITTED);
    corrections.saveAndFlush(correction);
    event(correction, "RESUBMITTED", decision.note().trim());
    return response(correction);
  }

  @Override
  @Transactional
  public InspectionCorrectionResponse resolve(
      String missionId, String id, InspectionCorrectionDecision decision) {
    var mission = lock(missionId);
    if (!authorization.canAssessMonitoringChecklist(missionId)) throw denied();
    var correction = required(missionId, id);
    editable(mission);
    version(correction, decision);
    if (correction.getStatus() != InspectionCorrectionStatus.SUBMITTED) throw conflict();
    var links = evidencePolicy.active(missionId);
    if (correction.getSupplementalMission() != null)
      correctionPolicy.acceptedSupplement(correction);
    else if (items.findItems(id).stream()
        .anyMatch(i -> !evidencePolicy.readyForInspectorApproval(i.getExecution(), links)))
      throw new ApiException(ErrorCode.CHECKLIST_NOT_READY);
    correction.setStatus(InspectionCorrectionStatus.RESOLVED);
    correction.setActiveMissionId(null);
    correction.setResolvedAt(Instant.now(clock));
    corrections.saveAndFlush(correction);
    event(correction, "RESOLVED", decision.note().trim());
    return response(correction);
  }

  @Override
  @Transactional
  public InspectionCorrectionResponse returnToStaff(
      String missionId, String id, InspectionCorrectionDecision decision) {
    var mission = lock(missionId);
    if (!authorization.canAssessMonitoringChecklist(missionId)) throw denied();
    var correction = required(missionId, id);
    editable(mission);
    version(correction, decision);
    if (correction.getStatus() != InspectionCorrectionStatus.SUBMITTED) throw conflict();
    correction.setStatus(InspectionCorrectionStatus.OPEN);
    corrections.saveAndFlush(correction);
    event(correction, "RETURNED", decision.note().trim());
    return response(correction);
  }

  @Override
  public void requireResolved(String missionId) {
    if (corrections.existsByActiveMissionId(missionId))
      throw new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING);
  }

  private Mission lock(String missionId) {
    var orderId =
        missions
            .findOrderIdByMissionId(missionId)
            .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    return aggregate.lockAggregate(orderId).missions().stream()
        .filter(m -> missionId.equals(m.getId()))
        .findFirst()
        .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
  }

  private void editable(Mission mission) {
    if (mission.getActualEndAt() == null
        || mission.getCancellationAt() != null
        || mission.getStatus() != MissionStatus.IN_PROGRESS)
      throw new ApiException(ErrorCode.MISSION_STATUS_INVALID);
    MissionRuntimeGate.requireFinalizedStructure(mission);
    scope.requireMaterialized(mission);
    if (results
        .findByMissionId(mission.getId())
        .map(
            r ->
                r.getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                    || r.getApprovalStatus()
                        == MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL)
        .orElse(false)) throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED);
  }

  private InspectionCorrection required(String missionId, String id) {
    var row =
        corrections.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!missionId.equals(row.getMission().getId())) throw denied();
    return row;
  }

  private void version(InspectionCorrection correction, InspectionCorrectionDecision request) {
    if (request == null
        || request.expectedVersion() == null
        || request.expectedVersion() < 0
        || request.note() == null
        || request.note().isBlank()
        || request.note().length() > 2000) throw invalid();
    if (!Objects.equals(request.reviewToken(), reviewToken(correction)))
      throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
    if (!request.expectedVersion().equals(correction.getVersion()))
      throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
  }

  private void event(InspectionCorrection correction, String action, String note) {
    var event = new InspectionCorrectionEvent();
    event.setCorrection(correction);
    event.setSequenceNumber(Math.incrementExact(events.countByCorrection_Id(correction.getId())));
    event.setAction(action);
    event.setActor(users.getCurrentUserId());
    event.setOccurredAt(Instant.now(clock));
    event.setNote(note);
    events.saveAndFlush(event);
  }

  private InspectionCorrectionResponse response(InspectionCorrection row) {
    return new InspectionCorrectionResponse(
        row.getId(),
        row.getMission().getId(),
        row.getVersion() == null ? 0 : row.getVersion(),
        row.getStatus(),
        reviewToken(row),
        row.getRequestedBy(),
        row.getRequestedAt(),
        row.getResolvedAt(),
        row.getSupplementalMission() == null ? null : row.getSupplementalMission().getId(),
        row.getSupplementalMission() == null ? null : row.getSupplementalMission().getMissionCode(),
        row.getSupplementalMission() == null
            ? null
            : row.getSupplementalMission().getStatus().name(),
        items.findItems(row.getId()).stream().map(mapper::item).toList(),
        events.findByCorrection_IdOrderBySequenceNumberAsc(row.getId()).stream()
            .map(mapper::event)
            .toList());
  }

  private String reviewToken(InspectionCorrection row) {
    var versions = new TreeMap<String, Long>();
    items
        .findItems(row.getId())
        .forEach(i -> versions.put(i.getExecution().getId(), i.getExecution().getVersion()));
    if (row.getSupplementalMission() != null) {
      executions
          .findOrderedByMissionId(row.getSupplementalMission().getId())
          .forEach(e -> versions.put(e.getId(), e.getVersion()));
      results
          .findByMissionId(row.getSupplementalMission().getId())
          .ifPresent(result -> versions.put("result:" + result.getId(), result.getVersion()));
    }
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(versions.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private ApiException denied() {
    return new ApiException(ErrorCode.ACCESS_DENIED);
  }

  private ApiException invalid() {
    return new ApiException(ErrorCode.INVALID_REQUEST);
  }

  private ApiException conflict() {
    return new ApiException(ErrorCode.INSPECTION_CORRECTION_STATE_CONFLICT);
  }
}
