package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionService;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;

class InspectionCorrectionServiceTest {
  MissionRepository missions = mock(MissionRepository.class);
  MissionResultRepository results = mock(MissionResultRepository.class);
  MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
  MissionChecklistEvidenceRepository evidence = mock(MissionChecklistEvidenceRepository.class);
  InspectionCorrectionRepository corrections = mock(InspectionCorrectionRepository.class);
  InspectionCorrectionItemRepository items = mock(InspectionCorrectionItemRepository.class);
  InspectionCorrectionEventRepository events = mock(InspectionCorrectionEventRepository.class);
  IMissionAuthorizationService auth = mock(IMissionAuthorizationService.class);
  AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
  IMissionChecklistScopeService scope = mock(IMissionChecklistScopeService.class);
  IChecklistEvidenceService policy = mock(IChecklistEvidenceService.class);
  IOrderExecutionCompletionPolicy aggregate = mock(IOrderExecutionCompletionPolicy.class);
  InspectionCorrectionPolicy correctionPolicy = mock(InspectionCorrectionPolicy.class);
  InspectionCorrectionService service =
      new InspectionCorrectionService(
          missions,
          results,
          executions,
          evidence,
          corrections,
          items,
          events,
          auth,
          users,
          scope,
          policy,
          Mappers.getMapper(InspectionCorrectionMapper.class),
          Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC),
          aggregate,
          correctionPolicy);
  Mission mission;
  MissionChecklistExecution execution;
  InspectionCorrection correction;
  List<InspectionCorrectionItem> savedItems;
  List<InspectionCorrectionEvent> savedEvents;

  @BeforeEach
  void setup() {
    org.springframework.test.util.ReflectionTestUtils.setField(service, "resultWorkflow", mock(IMissionResultService.class));
    org.springframework.test.util.ReflectionTestUtils.setField(service, "submissions", mock(com.ondemandmonitoring.mission.service.impl.MissionEvidenceSubmissionService.class));
    org.springframework.test.util.ReflectionTestUtils.setField(service, "evidenceEligibility", new com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy());
    var order = new Order();
    order.setId("order");
    order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
    order.getExecutionPlan().setRevision(2);
    mission = new Mission();
    mission.setId("mission");
    mission.setOrder(order);
    mission.setStatus(MissionStatus.IN_PROGRESS);
    mission.setActualEndAt(Instant.parse("2026-11-13T23:00:00Z"));
    var item = new OrderChecklistItem();
    item.setId("item");
    item.setOrder(order);
    item.setContent("Inspect fence");
    execution = new MissionChecklistExecution();
    execution.setId("exec");
    execution.setVersion(2L);
    execution.setMissionId("mission");
    execution.setOrderId("order");
    execution.setOrderChecklistItemId("item");
    execution.setOrderChecklistItem(item);
    execution.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
    execution.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
    when(missions.findOrderIdByMissionId("mission")).thenReturn(Optional.of("order"));
    when(aggregate.lockAggregate("order"))
        .thenReturn(new IOrderExecutionCompletionPolicy.LockedExecution(order, List.of(mission)));
    when(auth.canAssessMonitoringChecklist("mission")).thenReturn(true);
    when(auth.canRequestInspectionCorrection("mission")).thenReturn(true);
    when(auth.canAttachChecklistEvidence("mission")).thenReturn(true);
    when(users.getCurrentUserId()).thenReturn("actor");
    when(executions.findOrderedByMissionId("mission")).thenReturn(List.of(execution));
    when(evidence.findActiveByMissionId("mission")).thenReturn(List.of());
    savedItems = new ArrayList<>();
    savedEvents = new ArrayList<>();
    when(items.save(any()))
        .thenAnswer(
            call -> {
              var row = (InspectionCorrectionItem) call.getArgument(0);
              savedItems.add(row);
              return row;
            });
    when(items.findItems(anyString())).thenAnswer(call -> List.copyOf(savedItems));
    when(events.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              savedEvents.add(call.getArgument(0));
              return call.getArgument(0);
            });
    when(events.findByCorrection_IdOrderBySequenceNumberAsc(anyString()))
        .thenAnswer(call -> List.copyOf(savedEvents));
    when(corrections.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              correction = call.getArgument(0);
              if (correction.getId() == null) {
                correction.setId("correction");
                correction.setVersion(0L);
              } else correction.setVersion(correction.getVersion() + 1);
              return correction;
            });
    when(corrections.findById("correction")).thenAnswer(call -> Optional.ofNullable(correction));
  }

  InspectionCorrectionRequest request() {
    return new InspectionCorrectionRequest(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.DOSSIER_ADJUSTMENT,
        List.of(new InspectionCorrectionRequest.Item("exec", 2L, "Image is blurred", List.of())));
  }

  InspectionCorrectionResponse opened() {
    return service.request("mission", request());
  }

  InspectionCorrectionDecision decision(InspectionCorrectionResponse row) {
    return new InspectionCorrectionDecision(
        row.version(), "Checked the corrected evidence", row.reviewToken());
  }

  @Test
  void sourceInspectorCanReadAcceptedSupplementWithoutChildAssignment() {
    opened();
    correction.setMode(com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.SUPPLEMENTAL_FLIGHT);
    var child = new Mission(); child.setId("child"); child.setMissionCode("MS-12345");
    when(correctionPolicy.acceptedSupplement(correction)).thenReturn(child);
    when(executions.findOrderedByMissionId("child")).thenReturn(List.of(execution));
    var mapper = Mappers.getMapper(com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "executionMapper", mapper);
    when(policy.enrich(any(), eq(execution), anyList())).thenAnswer(call -> call.getArgument(0));
    var response = service.supplementResult("mission", "correction");
    assertThat(response.missionId()).isEqualTo("child");
    assertThat(response.executions()).hasSize(1);
    assertThat(response.executions().getFirst().isCustomerSelectionEditable()).isFalse();
    verify(auth, never()).canViewMission("child");
  }

  @Test
  void supplementReadRejectsOtherSourceAndUnassignedStaff() {
    opened();
    when(auth.canRequestInspectionCorrection("other")).thenReturn(true);
    assertThatThrownBy(() -> service.supplementResult("other", "correction")).isInstanceOf(ApiException.class);
    when(auth.canRequestInspectionCorrection("mission")).thenReturn(false);
    assertThatThrownBy(() -> service.supplementResult("mission", "correction")).isInstanceOf(ApiException.class);
    verify(correctionPolicy, never()).acceptedSupplement(any());
  }

  @Test
  void supplementReadRequiresAcceptedFlightResult() {
    opened();
    assertThatThrownBy(() -> service.supplementResult("mission", "correction")).isInstanceOf(ApiException.class);
    correction.setMode(com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.SUPPLEMENTAL_FLIGHT);
    when(correctionPolicy.acceptedSupplement(correction)).thenThrow(new ApiException(ErrorCode.INSPECTION_CORRECTION_PENDING));
    assertThatThrownBy(() -> service.supplementResult("mission", "correction")).isInstanceOf(ApiException.class);
    verify(policy, never()).enrich(any(), any(), any());
  }

  @Test
  void inspectorCreatesScopedRequestWithoutResettingAcceptedWork() {
    var response = opened();
    assertThat(response.items()).hasSize(1);
    assertThat(response.items().getFirst().reason()).isEqualTo("Image is blurred");
    assertThat(response.status()).isEqualTo(InspectionCorrectionStatus.OPEN);
    assertThat(execution.getAssessmentStatus()).isEqualTo(ChecklistAssessmentStatus.NON_COMPLIANT);
    verify(executions, never()).save(any());
    assertThat(savedEvents.getFirst().getAction()).isEqualTo("REQUESTED");
  }

  @Test
  void onlyAssignedInspectorCanRequest() {
    when(auth.canAssessMonitoringChecklist("mission")).thenReturn(false);
    assertThatThrownBy(this::opened).isInstanceOf(ApiException.class);
    verify(corrections, never()).saveAndFlush(any());
  }

  @Test
  void staleExecutionVersionCannotCreateRequest() {
    execution.setVersion(3L);
    assertThatThrownBy(this::opened).isInstanceOf(ApiException.class);
    verify(corrections, never()).saveAndFlush(any());
  }

  @Test
  void siblingExecutionCannotBeRequested() {
    execution.setMissionId("sibling");
    assertThatThrownBy(this::opened).isInstanceOf(ApiException.class);
  }

  @Test
  void unknownMediaCannotBeAddedToRequest() {
    var r = request();
    var withMedia =
        new InspectionCorrectionRequest(
            r.clientRequestId(), r.mode(),
            List.of(
                new InspectionCorrectionRequest.Item(
                    "exec", 2L, "Wrong image", List.of("foreign-media"))));
    assertThatThrownBy(() -> service.request("mission", withMedia))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void duplicateExecutionTargetsFailAtomically() {
    var r = request();
    assertThatThrownBy(
            () ->
                service.request(
                    "mission",
                    new InspectionCorrectionRequest(
                        r.clientRequestId(), r.mode(), List.of(r.items().getFirst(), r.items().getFirst()))))
        .isInstanceOf(ApiException.class);
    verify(corrections, never()).saveAndFlush(any());
  }

  @Test
  void identicalClientCommandReplaysWithoutDuplicatingHistory() {
    opened();
    when(corrections.findByMission_IdAndClientRequestId(
            "mission", request().clientRequestId().toString()))
        .thenReturn(Optional.of(correction));
    opened();
    assertThat(savedItems).hasSize(1);
    assertThat(savedEvents).hasSize(1);
  }

  @Test
  void reusedCommandWithDifferentReasonFails() {
    opened();
    when(corrections.findByMission_IdAndClientRequestId(
            "mission", request().clientRequestId().toString()))
        .thenReturn(Optional.of(correction));
    var r =
        new InspectionCorrectionRequest(
            request().clientRequestId(), request().mode(),
            List.of(
                new InspectionCorrectionRequest.Item("exec", 2L, "Different reason", List.of())));
    assertThatThrownBy(() -> service.request("mission", r)).isInstanceOf(ApiException.class);
  }

  @Test
  void onlyOneActiveRequestCanExist() {
    when(corrections.existsByActiveMissionId("mission")).thenReturn(true);
    assertThatThrownBy(this::opened).isInstanceOf(ApiException.class);
  }

  @Test
  void incompleteEvidenceCannotBeResubmitted() {
    var row = opened();
    assertThatThrownBy(() -> service.resubmit("mission", row.id(), decision(row)))
        .isInstanceOf(ApiException.class);
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.OPEN);
  }

  @Test
  void staffResubmitsInspectorChecksAndHistoryIsRetained() {
    var row = opened();
    when(policy.ready(eq(execution), anyList(), eq(false))).thenReturn(true);
    var pending = service.resubmit("mission", row.id(), decision(row));
    assertThat(pending.status()).isEqualTo(InspectionCorrectionStatus.SUBMITTED);
    when(policy.readyForInspectorApproval(eq(execution), anyList())).thenReturn(true);
    var resolved = service.resolve("mission", row.id(), decision(pending));
    assertThat(resolved.status()).isEqualTo(InspectionCorrectionStatus.RESOLVED);
    assertThat(correction.getActiveMissionId()).isNull();
    assertThat(savedEvents)
        .extracting(InspectionCorrectionEvent::getAction)
        .containsExactly("REQUESTED", "RESUBMITTED", "RESOLVED");
  }

  @Test
  void inspectorCannotResolveBeforeStaffResubmits() {
    var row = opened();
    assertThatThrownBy(() -> service.resolve("mission", row.id(), decision(row)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void inspectorCanReturnSubmittedWorkWithoutDeletingHistory() {
    var row = opened();
    when(policy.ready(eq(execution), anyList(), eq(false))).thenReturn(true);
    var pending = service.resubmit("mission", row.id(), decision(row));
    var returned = service.returnToStaff("mission", row.id(), decision(pending));
    assertThat(returned.status()).isEqualTo(InspectionCorrectionStatus.OPEN);
    assertThat(savedEvents).hasSize(3);
  }

  @Test
  void reviewedExecutionMustNotChangeBeforeAcceptingCorrection() {
    var row = opened();
    execution.setVersion(3L);
    assertThatThrownBy(() -> service.resubmit("mission", row.id(), decision(row)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void approvalAndPublicationGateIsClosedWhileCorrectionOpen() {
    when(corrections.existsByActiveMissionId("mission")).thenReturn(true);
    assertThatThrownBy(() -> service.requireResolved("mission")).isInstanceOf(ApiException.class);
  }

  @Test
  void publishedOrApprovedResultCannotBeReturnedToStaff() {
    var result = new MissionResult();
    result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
    when(results.findByMissionId("mission")).thenReturn(Optional.of(result));
    assertThatThrownBy(this::opened).isInstanceOf(ApiException.class);
  }

  @Test
  void inspectorCannotSubmitAsPilot() {
    var row = opened();
    when(auth.canAttachChecklistEvidence("mission")).thenReturn(false);
    assertThatThrownBy(() -> service.resubmit("mission", row.id(), decision(row)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void readsHaveNoMutation() {
    when(missions.findById("mission")).thenReturn(Optional.of(mission));
    when(auth.canViewMission("mission")).thenReturn(true);
    when(corrections.findByMission_IdOrderByRequestedAtDescIdDesc("mission")).thenReturn(List.of());
    assertThat(service.list("mission")).isEmpty();
    verify(corrections, never()).saveAndFlush(any());
  }

  @Test void cannotRequestCorrectionForCompliantOrUnassessedChecklist() {
    for(var status:List.of(ChecklistAssessmentStatus.COMPLIANT,ChecklistAssessmentStatus.NOT_ASSESSED)){
      execution.setAssessmentStatus(status);
      assertThatThrownBy(()->opened()).isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.CHECKLIST_NOT_READY);
    }
    verify(corrections,never()).saveAndFlush(any());
  }
  @Test void aCorrectionMustIncludeAllFailedChecklistItems() {
    var other=new MissionChecklistExecution();other.setId("other");other.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
    when(executions.findOrderedByMissionId("mission")).thenReturn(List.of(execution,other));
    assertThatThrownBy(()->opened()).isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.CHECKLIST_NOT_READY);
    verify(corrections,never()).saveAndFlush(any());
  }
  private Mission acceptedChild() {
    opened();
    correction.setMode(com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.SUPPLEMENTAL_FLIGHT);
    var child = new Mission(); child.setId("child"); child.setMissionCode("MS-CHILD");
    child.setOrder(mission.getOrder()); child.setStatus(MissionStatus.COMPLETED);
    child.setInspectionCorrection(correction); correction.setSupplementalMission(child);
    when(correctionPolicy.acceptedSupplement(correction)).thenReturn(child);
    return child;
  }
  @Test void acceptedChildResolvesTheRequestWithoutSourceReassessment() {
    var child = acceptedChild();
    service.registerAcceptedSupplement(child);
    service.registerAcceptedSupplement(child);
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.RESOLVED);
    assertThat(execution.getAssessmentStatus()).isEqualTo(ChecklistAssessmentStatus.NON_COMPLIANT);
    assertThat(savedEvents.stream().filter(e -> e.getAction().equals("SUPPLEMENT_ACCEPTED"))).hasSize(1);
    verify(correctionPolicy).acceptedSupplement(correction);
  }
  @Test void recoveryRequiresOriginalInspectorAndVerifiesAcceptedChild() {
    acceptedChild();
    when(auth.canRequestInspectionCorrection("mission")).thenReturn(false);
    assertThatThrownBy(() -> service.synchronizeSupplement("mission", "correction")).isInstanceOf(ApiException.class);
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.OPEN);
    when(auth.canRequestInspectionCorrection("mission")).thenReturn(true);
    assertThat(service.synchronizeSupplement("mission", "correction").status()).isEqualTo(InspectionCorrectionStatus.RESOLVED);
  }
  @Test void incompleteOrRejectedSupplementNeverChangesSourceRequest() {
    var child = acceptedChild();
    when(correctionPolicy.acceptedSupplement(correction)).thenThrow(new ApiException(ErrorCode.CHECKLIST_NOT_READY));
    assertThatThrownBy(() -> service.registerAcceptedSupplement(child)).isInstanceOf(ApiException.class);
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.OPEN);
    assertThat(savedEvents.stream().filter(e -> e.getAction().equals("SUPPLEMENT_ACCEPTED"))).isEmpty();
  }
  @Test void supersededChildCannotCompleteTheCurrentRequest() {
    var child = acceptedChild();
    var replacement = new Mission(); replacement.setId("new-attempt"); correction.setSupplementalMission(replacement);
    assertThatThrownBy(() -> service.registerAcceptedSupplement(child)).isInstanceOf(ApiException.class);
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.OPEN);
  }


  @Test
  void checklistReviewResolvesOnlyWhenCustomerEvidenceIsReady() {
    var row = opened();
    when(policy.ready(eq(execution), anyList(), eq(false))).thenReturn(true);
    service.resubmit("mission", row.id(), decision(row));
    assertThat(execution.getAssessmentStatus()).isEqualTo(ChecklistAssessmentStatus.NOT_ASSESSED);
    when(corrections.findByMission_IdOrderByRequestedAtDescIdDesc("mission")).thenReturn(List.of(correction));
    execution.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
    service.reconcileChecklistReview("mission");
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.SUBMITTED);
    when(policy.readyForInspectorApproval(eq(execution), anyList())).thenReturn(true);
    service.reconcileChecklistReview("mission");
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.RESOLVED);
    service.reconcileChecklistReview("mission");
    assertThat(savedEvents).extracting(InspectionCorrectionEvent::getAction)
        .containsExactly("REQUESTED", "RESUBMITTED", "RESOLVED");
  }

  @Test
  void checklistReviewReturnsFailedWorkWithItsAssessmentReason() {
    var row = opened();
    when(policy.ready(eq(execution), anyList(), eq(false))).thenReturn(true);
    service.resubmit("mission", row.id(), decision(row));
    when(corrections.findByMission_IdOrderByRequestedAtDescIdDesc("mission")).thenReturn(List.of(correction));
    service.reconcileChecklistReview("mission");
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.SUBMITTED);
    execution.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
    execution.setAssessmentNote("Still blurred");
    service.reconcileChecklistReview("mission");
    assertThat(correction.getStatus()).isEqualTo(InspectionCorrectionStatus.OPEN);
    assertThat(savedEvents.get(savedEvents.size() - 1).getAction()).isEqualTo("RETURNED");
  }

  @Test
  void cannotOpenAnUnexecutableNestedFlightCorrection() {
    mission.setInspectionCorrection(new InspectionCorrection());
    var initial = request();
    var nested = new InspectionCorrectionRequest(initial.clientRequestId(),
        com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.SUPPLEMENTAL_FLIGHT, initial.items());
    assertThatThrownBy(() -> service.request("mission", nested)).isInstanceOf(ApiException.class);
    verify(corrections, never()).saveAndFlush(any());
  }

  @Test
  void nestedDossierCannotBeConvertedIntoAnUnexecutableFlight() {
    var opened = opened();
    mission.setInspectionCorrection(new InspectionCorrection());
    assertThatThrownBy(() -> service.requireFlight("mission", opened.id(), decision(opened)))
        .isInstanceOf(ApiException.class);
    assertThat(correction.getMode()).isEqualTo(
        com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.DOSSIER_ADJUSTMENT);
  }
}
