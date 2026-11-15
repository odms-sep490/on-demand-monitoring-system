package com.ondemandmonitoring.mission.inspection.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionEvent;
import com.ondemandmonitoring.mission.inspection.dto.request.InternalCorrectionMissionRequest;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionEventRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.inspection.service.IInternalInspectionMissionService;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class InternalInspectionMissionService implements IInternalInspectionMissionService {
  private final IOrderExecutionCompletionPolicy aggregate;
  private final MissionRepository missions;
  private final OrderRepository orders;
  private final InvoiceRepository invoices;
  private final InspectionCorrectionRepository corrections;
  private final InspectionCorrectionItemRepository items;
  private final IMissionChecklistAssignmentService assignments;
  private final IMissionPaymentEligibilityService payment;
  private final MissionPlanningActivationService activation;
  private final MissionMapper mapper;
  private final Clock clock;
  private final InspectionCorrectionEventRepository events;
  private final com.ondemandmonitoring.user.service.AuthenticatedUserResolver users;

  @Override
  public MissionResponse create(
      String sourceId, String correctionId, InternalCorrectionMissionRequest request) {
    var correction = lock(sourceId, correctionId);
    var order = correction.getMission().getOrder();
    if (request == null
        || request.clientRequestId() == null
        || request.expectedVersion() == null
        || request.scopeLabel() == null
        || request.scopeLabel().isBlank()
        || request.scopeLabel().length() > 255) throw new ApiException(ErrorCode.INVALID_REQUEST);
    String fingerprint = fingerprint(correctionId, request);
    var replay = missions.findByReworkRequestId(request.clientRequestId());
    if (replay.isPresent()) {
      var prior = replay.get();
      if (prior.getInspectionCorrection() == null
          || !correctionId.equals(prior.getInspectionCorrection().getId())
          || !fingerprint.equals(prior.getReworkRequestFingerprint()))
        throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
      return mapper.toResponse(prior);
    }
    requireOpen(correction);
    if (!request.expectedVersion().equals(correction.getVersion()))
      throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
    if (correction.getMission().getInspectionCorrection() != null)
      throw new ApiException(
          ErrorCode.INVALID_REQUEST, "Nested internal correction missions are not supported");
    var previous = correction.getSupplementalMission();
    if (previous != null && previous.getStatus() != MissionStatus.CANCELLED)
      throw new ApiException(ErrorCode.INSPECTION_CORRECTION_STATE_CONFLICT);
    schedule(request.scheduledStartAt(), request.scheduledEndAt());
    var selections = items.findItems(correctionId);
    if (selections.isEmpty()) throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
    var mission = new Mission();
    mission.setOrder(order);
    mission.setMissionCode(
        "MS-I"
            + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT));
    mission.setPurpose(MissionPurpose.REWORK);
    mission.setInspectionCorrection(correction);
    mission.setStatus(MissionStatus.PLANNING);
    mission.setScopeLabel(request.scopeLabel().trim());
    mission.setScheduledStartAt(request.scheduledStartAt());
    mission.setScheduledEndAt(request.scheduledEndAt());
    FlightPermit.pendingFor(mission);
    mission.setReworkRequestId(request.clientRequestId());
    mission.setReworkRequestFingerprint(fingerprint);
    missions.saveAndFlush(mission);
    correction.setSupplementalMission(mission);
    corrections.saveAndFlush(correction);
    assignments.replace(
        mission.getId(),
        new ChecklistAssignmentSelectionRequest(
            order.getExecutionPlan().getRevision(),
            selections.stream()
                .map(
                    i ->
                        new ChecklistAssignmentSelectionRequest.Item(
                            i.getExecution().getOrderChecklistItemId()))
                .toList()));
    audit(correction, "INTERNAL_MISSION_CREATED", mission.getMissionCode());
    return mapper.toResponse(mission);
  }

  @Override
  public MissionResponse finalizePlan(
      String sourceId, String correctionId, ExecutionPlanFinalizeRequest request) {
    var correction = lock(sourceId, correctionId);
    requireOpen(correction);
    var order = correction.getMission().getOrder();
    if (request == null
        || !Objects.equals(request.expectedPlanRevision(), order.getExecutionPlan().getRevision())
        || order.getExecutionPlan().getRevision() == Long.MAX_VALUE)
      throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
    var mission = correction.getSupplementalMission();
    if (mission == null
        || mission.getInspectionCorrection() == null
        || !correctionId.equals(mission.getInspectionCorrection().getId()))
      throw new ApiException(ErrorCode.INVALID_REQUEST);
    if (mission.getStatus() != MissionStatus.PLANNING || mission.getCancellationAt() != null)
      throw new ApiException(ErrorCode.MISSION_PLANNING_ONLY);
    schedule(mission.getScheduledStartAt(), mission.getScheduledEndAt());
    if (!payment.isResourceAssignmentReady(mission))
      throw new ApiException(ErrorCode.RESOURCE_TIME_CONFLICT);
    var selected = assignments.get(mission.getId());
    var expected =
        items.findItems(correctionId).stream()
            .map(i -> i.getExecution().getOrderChecklistItemId())
            .collect(java.util.stream.Collectors.toSet());
    if (selected == null
        || !mission.getId().equals(selected.missionId())
        || selected.assignments().size() != expected.size()
        || !selected.assignments().stream()
            .allMatch(a -> a.active() && expected.contains(a.orderChecklistItemId())))
      throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
    // Scope is checked again by the canonical materializer at activation.
    activation.activate(mission);
    order
        .getExecutionPlan()
        .setRevision(Math.incrementExact(order.getExecutionPlan().getRevision()));
    orders.saveAndFlush(order);
    missions.saveAndFlush(mission);
    audit(correction, "INTERNAL_PLAN_FINALIZED", mission.getMissionCode());
    return mapper.toResponse(mission);
  }

  private InspectionCorrection lock(String sourceId, String correctionId) {
    var orderId =
        missions
            .findOrderIdByMissionId(sourceId)
            .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    var locked = aggregate.lockAggregate(orderId);
    var correction =
        corrections
            .findById(correctionId)
            .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!sourceId.equals(correction.getMission().getId())
        || !orderId.equals(correction.getMission().getOrder().getId()))
      throw new ApiException(ErrorCode.ACCESS_DENIED);
    return correction;
  }

  private void requireOpen(InspectionCorrection correction) {
    var source = correction.getMission();
    var order = source.getOrder();
    if (correction.getStatus() != InspectionCorrectionStatus.OPEN
        || source.getActualEndAt() == null
        || source.getStatus() != MissionStatus.IN_PROGRESS
        || source.getCancellationAt() != null)
      throw new ApiException(ErrorCode.INSPECTION_CORRECTION_STATE_CONFLICT);
    if (!Set.of(OrderStatus.APPROVED, OrderStatus.IN_PROGRESS).contains(order.getOrderStatus()))
      throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
    MissionRuntimeGate.requireFinalizedStructure(source);
    var invoice =
        invoices
            .findByOrderId(order.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    if (!payment.isDepositSatisfied(invoice)) throw new ApiException(ErrorCode.INVOICE_NOT_PAID);
  }

  private void schedule(Instant start, Instant end) {
    if (start == null
        || end == null
        || !start.isAfter(Instant.now(clock))
        || !start.isBefore(end)
        || !start
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
            .toLocalDate()
            .equals(end.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()))
      throw new ApiException(ErrorCode.INVALID_REQUEST);
  }

  private void audit(InspectionCorrection correction, String action, String note) {
    var event = new InspectionCorrectionEvent();
    event.setCorrection(correction);
    event.setSequenceNumber(Math.incrementExact(events.countByCorrection_Id(correction.getId())));
    event.setAction(action);
    event.setNote(note);
    event.setActor(users.getCurrentUserId());
    event.setOccurredAt(Instant.now(clock));
    events.saveAndFlush(event);
  }

  private String fingerprint(String id, InternalCorrectionMissionRequest request) {
    try {
      String canonical =
          id
              + "|"
              + request.expectedVersion()
              + "|"
              + request.scopeLabel()
              + "|"
              + request.scheduledStartAt()
              + "|"
              + request.scheduledEndAt();
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
