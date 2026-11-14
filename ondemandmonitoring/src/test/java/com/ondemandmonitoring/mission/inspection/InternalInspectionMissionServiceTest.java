package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection;
import com.ondemandmonitoring.mission.inspection.domain.InspectionCorrectionItem;
import com.ondemandmonitoring.mission.inspection.dto.request.InternalCorrectionMissionRequest;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionEventRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.inspection.service.impl.InternalInspectionMissionService;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService;
import com.ondemandmonitoring.mission.service.impl.MissionPlanningActivationService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class InternalInspectionMissionServiceTest {
  IOrderExecutionCompletionPolicy aggregate = mock(IOrderExecutionCompletionPolicy.class);
  MissionRepository missions = mock(MissionRepository.class);
  OrderRepository orders = mock(OrderRepository.class);
  InvoiceRepository invoices = mock(InvoiceRepository.class);
  InspectionCorrectionRepository corrections = mock(InspectionCorrectionRepository.class);
  InspectionCorrectionItemRepository items = mock(InspectionCorrectionItemRepository.class);
  IMissionChecklistAssignmentService assignments = mock(IMissionChecklistAssignmentService.class);
  IMissionPaymentEligibilityService payment = mock(IMissionPaymentEligibilityService.class);
  MissionPlanningActivationService activation = mock(MissionPlanningActivationService.class);
  MissionMapper mapper = mock(MissionMapper.class);
  InspectionCorrectionEventRepository events = mock(InspectionCorrectionEventRepository.class);
  AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
  InternalInspectionMissionService service =
      new InternalInspectionMissionService(
          aggregate,
          missions,
          orders,
          invoices,
          corrections,
          items,
          assignments,
          payment,
          activation,
          mapper,
          Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC),
          events,
          users);
  Mission parent;
  InspectionCorrection correction;
  Order order;
  Invoice invoice;
  Mission saved;

  @BeforeEach
  void setup() {
    order = new Order();
    order.setId("order");
    order.setOrderStatus(OrderStatus.IN_PROGRESS);
    order.getExecutionPlan().setRevision(2);
    order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
    parent = new Mission();
    parent.setId("parent");
    parent.setOrder(order);
    parent.setStatus(MissionStatus.IN_PROGRESS);
    parent.setActualEndAt(Instant.parse("2026-11-13T23:00:00Z"));
    correction = new InspectionCorrection();
    correction.setId("c");
    correction.setMission(parent);
    correction.setVersion(0L);
    correction.setStatus(InspectionCorrectionStatus.OPEN);
    when(missions.findOrderIdByMissionId("parent")).thenReturn(Optional.of("order"));
    when(aggregate.lockAggregate("order"))
        .thenReturn(new IOrderExecutionCompletionPolicy.LockedExecution(order, List.of(parent)));
    when(corrections.findById("c")).thenReturn(Optional.of(correction));
    invoice = new Invoice();
    when(invoices.findByOrderId("order")).thenReturn(Optional.of(invoice));
    when(payment.isDepositSatisfied(invoice)).thenReturn(true);
    var execution = new MissionChecklistExecution();
    execution.setOrderChecklistItemId("bad-item");
    var item = new InspectionCorrectionItem();
    item.setExecution(execution);
    when(items.findItems("c")).thenReturn(List.of(item));
    when(missions.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              saved = call.getArgument(0);
              if (saved.getId() == null) saved.setId("child");
              return saved;
            });
    when(mapper.toResponse(any()))
        .thenAnswer(
            call -> {
              var m = (Mission) call.getArgument(0);
              return MissionResponse.builder().id(m.getId()).purpose(m.getPurpose()).build();
            });
  }

  InternalCorrectionMissionRequest request() {
    return new InternalCorrectionMissionRequest(
        0L,
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        "Recapture fence",
        Instant.parse("2026-11-15T01:00:00Z"),
        Instant.parse("2026-11-15T02:00:00Z"));
  }

  @Test
  void createsSeparateMissionForOnlyRequestedScopeWithoutReopeningOriginal() {
    var response = service.create("parent", "c", request());
    assertThat(response.getId()).isEqualTo("child");
    assertThat(saved.getInspectionCorrection()).isSameAs(correction);
    assertThat(saved.getResultRevisionCaseId()).isNull();
    assertThat(saved.getPurpose()).isEqualTo(MissionPurpose.REWORK);
    assertThat(parent.getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
    verify(assignments)
        .replace(
            eq("child"),
            argThat(
                r ->
                    r.items().size() == 1
                        && r.items().getFirst().orderChecklistItemId().equals("bad-item")));
    verify(orders, never()).saveAndFlush(any());
  }

  @Test
  void paidDepositAndContractGateStillApply() {
    when(payment.isDepositSatisfied(invoice)).thenReturn(false);
    assertThatThrownBy(() -> service.create("parent", "c", request()))
        .isInstanceOf(ApiException.class);
    verify(missions, never()).saveAndFlush(any());
  }

  @Test
  void replayDoesNotCreateAnotherMission() {
    service.create("parent", "c", request());
    when(missions.findByReworkRequestId(request().clientRequestId()))
        .thenReturn(Optional.of(saved));
    service.create("parent", "c", request());
    verify(missions, times(1)).saveAndFlush(any());
  }

  @Test
  void cannotCreateSiblingOrNestedSupplement() {
    assertThatThrownBy(() -> service.create("parent", "wrong", request()))
        .isInstanceOf(ApiException.class);
    parent.setInspectionCorrection(new InspectionCorrection());
    assertThatThrownBy(() -> service.create("parent", "c", request()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void cannotCreateSecondActiveSupplement() {
    service.create("parent", "c", request());
    var r = request();
    assertThatThrownBy(
            () ->
                service.create(
                    "parent",
                    "c",
                    new InternalCorrectionMissionRequest(
                        correction.getVersion(),
                        UUID.randomUUID(),
                        r.scopeLabel(),
                        r.scheduledStartAt(),
                        r.scheduledEndAt())))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void finalizationRequiresActualResourceReservations() {
    service.create("parent", "c", request());
    assertThatThrownBy(
            () -> service.finalizePlan("parent", "c", new ExecutionPlanFinalizeRequest(2L, true)))
        .isInstanceOf(ApiException.class);
    verify(activation, never()).activate(any());
  }

  @Test
  void finalizationUsesExistingActivationAndAdvancesPlanRevision() {
    service.create("parent", "c", request());
    when(payment.isResourceAssignmentReady(saved)).thenReturn(true);
    when(assignments.get("child"))
        .thenReturn(
            new com.ondemandmonitoring.mission.dto.response.ChecklistAssignmentSelectionResponse(
                "child",
                2L,
                List.of(
                    new com.ondemandmonitoring.mission.dto.response.ChecklistAssignmentResponse(
                        "assignment",
                        "bad-item",
                        "Fence",
                        com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM,
                        0,
                        true,
                        0L,
                        1,
                        com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE))));
    service.finalizePlan("parent", "c", new ExecutionPlanFinalizeRequest(2L, true));
    verify(activation).activate(saved);
    assertThat(order.getExecutionPlan().getRevision()).isEqualTo(3);
  }

  @Test
  void expiredScheduleCannotBeActivated() {
    service.create("parent", "c", request());
    saved.setScheduledStartAt(Instant.parse("2026-11-13T01:00:00Z"));
    assertThatThrownBy(
            () -> service.finalizePlan("parent", "c", new ExecutionPlanFinalizeRequest(2L, true)))
        .isInstanceOf(ApiException.class);
  }
}
