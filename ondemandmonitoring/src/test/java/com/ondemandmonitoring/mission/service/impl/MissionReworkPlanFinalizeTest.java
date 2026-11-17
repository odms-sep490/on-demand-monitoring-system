package com.ondemandmonitoring.mission.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.delivery.domain.*;
import com.ondemandmonitoring.delivery.enums.*;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionChecklistMaterializationService;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.domain.User;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MissionReworkPlanFinalizeTest {
    final MissionService service = mock(MissionService.class, CALLS_REAL_METHODS);
    final MissionRepository missions = mock(MissionRepository.class);
    final OrderRepository orders = mock(OrderRepository.class);
    final ResultRevisionCaseRepository cases = mock(ResultRevisionCaseRepository.class);
    final ResultRevisionCaseItemRepository caseItems = mock(ResultRevisionCaseItemRepository.class);
    final MissionChecklistAssignmentRepository assignments = mock(MissionChecklistAssignmentRepository.class);
    final MissionStaffAssignmentRepository crew = mock(MissionStaffAssignmentRepository.class);
    final MissionDeviceAssignmentRepository devices = mock(MissionDeviceAssignmentRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final IMissionPaymentEligibilityService payments = mock(IMissionPaymentEligibilityService.class);
    final IMissionChecklistMaterializationService materialization = mock(IMissionChecklistMaterializationService.class);
    final Order order = new Order();
    final Mission mission = new Mission();
    final ResultRevisionCase revisionCase = new ResultRevisionCase();
    final ResultRevisionCaseItem item = new ResultRevisionCaseItem();
    List<MissionStaffAssignment> staff;
    MissionDeviceAssignment device;

    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(service, "missionRepository", missions);
        ReflectionTestUtils.setField(service, "orderRepository", orders);
        ReflectionTestUtils.setField(service, "resultRevisionCases", cases);
        ReflectionTestUtils.setField(service, "resultRevisionCaseItems", caseItems);
        ReflectionTestUtils.setField(service, "missionChecklistAssignmentRepository", assignments);
        ReflectionTestUtils.setField(service, "missionStaffAssignmentRepository", crew);
        ReflectionTestUtils.setField(service, "missionDeviceAssignmentRepository", devices);
        ReflectionTestUtils.setField(service, "invoiceRepository", invoices);
        ReflectionTestUtils.setField(service, "missionPaymentEligibilityService", payments);
        ReflectionTestUtils.setField(service, "missionPlanningActivationService",
                new MissionPlanningActivationService(materialization, crew, devices));
        ReflectionTestUtils.setField(service, "missionMapper", mock(MissionMapper.class));
        order.setId("order"); order.setOrderStatus(OrderStatus.COMPLETED);
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED); order.getExecutionPlan().setRevision(7);
        mission.setScheduledStartAt(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(2)
                .atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
        mission.setScheduledEndAt(mission.getScheduledStartAt().plusSeconds(3600));
        mission.setId("rework"); mission.setOrder(order); mission.setPurpose(MissionPurpose.REWORK);
        mission.setResultRevisionCaseId("case"); mission.setStatus(MissionStatus.PLANNING);
        revisionCase.setId("case"); revisionCase.setOrder(order); revisionCase.setActive(true);
        revisionCase.setStatus(ResultRevisionCaseStatus.OPEN);
        item.setScopeDecision(ResultRevisionScope.IN_SCOPE); item.setStrategy(ResultRevisionStrategy.REWORK);
        item.setAssignedReworkMissionId("rework");
        when(cases.findById("case")).thenReturn(Optional.of(revisionCase));
        when(cases.findByIdForUpdate("case")).thenReturn(Optional.of(revisionCase));
        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(missions.findAllByOrderIdForUpdateOrderById("order")).thenReturn(List.of(mission));
        when(caseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc("case")).thenReturn(List.of(item));
        var checklist = new OrderChecklistItem(); checklist.setId("checklist");
        var assignment = new MissionChecklistAssignment(); assignment.setOrderChecklistItem(checklist); assignment.setOrderChecklistItemId("checklist");
        when(assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc("rework"))
                .thenReturn(List.of(assignment));
        when(caseItems.findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc("case", List.of("checklist")))
                .thenReturn(List.of(item));
        staff = Arrays.stream(MissionStaffRole.values()).map(role -> {
            var row = new MissionStaffAssignment(); row.setStaff(new User()); row.setAssignedRole(role);
            row.setResponseStatus(MissionStaffAssignmentStatus.PLANNING); return row;
        }).toList();
        when(crew.findByMissionId("rework")).thenReturn(staff);
        device = new MissionDeviceAssignment(); device.setDevice(new Device()); device.setDeviceRole(DeviceRole.MAIN);
        device.setCheckupStatus(MissionDeviceCheckStatus.PLANNING);
        when(devices.findByMissionId("rework")).thenReturn(List.of(device));
        var invoice = new Invoice();
        when(invoices.findByOrderId("order")).thenReturn(Optional.of(invoice));
        when(payments.isDepositSatisfied(invoice)).thenReturn(true);
        when(payments.isResourceAssignmentReady(mission)).thenReturn(true);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = OrderStatus.class, names = {"IN_PROGRESS", "COMPLETED"})
    void finalizesReworkUsingSharedMaterializationAndResourceActivationWithoutReopeningOriginalPlan(OrderStatus status) {
        order.setOrderStatus(status);
        service.finalizeReworkPlan("case", new ExecutionPlanFinalizeRequest(7L, true));
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.PENDING_CONFIRMATION);
        assertThat(order.getExecutionPlan().getStatus()).isEqualTo(ExecutionPlanStatus.FINALIZED);
        assertThat(staff).allSatisfy(row -> assertThat(row.getResponseStatus()).isEqualTo(MissionStaffAssignmentStatus.PENDING));
        assertThat(device.getCheckupStatus()).isEqualTo(MissionDeviceCheckStatus.PENDING);
        verify(materialization).materialize(mission);
    }

    @Test
    void creatingReworkForAnInProgressOrderStillEnforcesFullPayment() {
        order.setOrderStatus(OrderStatus.IN_PROGRESS);
        revisionCase.setVersion(2L);
        var invoice = invoices.findByOrderId("order").orElseThrow();
        when(payments.isDepositSatisfied(invoice)).thenReturn(false);
        var request = new com.ondemandmonitoring.mission.dto.request.CreateResultRevisionReworkMissionRequest(
                2L, UUID.randomUUID(), "Rework roof", java.time.Instant.parse("2099-11-11T03:00:00Z"),
                java.time.Instant.parse("2099-11-11T04:00:00Z"), List.of("case-item"));
        assertThatThrownBy(() -> service.createResultRevisionReworkMission("case", request))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVOICE_NOT_PAID);
        verify(missions, never()).saveAndFlush(any());
    }

    @Test
    void missingCrewKeepsThePlanUnfinalized() {
        when(crew.findByMissionId("rework")).thenReturn(List.of());
        assertThatThrownBy(() -> service.finalizeReworkPlan("case", new ExecutionPlanFinalizeRequest(7L, true)))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.PLANNING);
        verifyNoInteractions(materialization);
    }

    @Test
    void missingDroneKeepsThePlanUnfinalized() {
        when(devices.findByMissionId("rework")).thenReturn(List.of());
        assertThatThrownBy(() -> service.finalizeReworkPlan("case", new ExecutionPlanFinalizeRequest(7L, true)))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.PLANNING);
        verifyNoInteractions(materialization);
    }

    @Test
    void permitCannotBeApprovedBeforeReworkPlanFinalization() {
        var permits = mock(com.ondemandmonitoring.flightpermit.service.impl.FlightPermitServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(permits, "missionRepository", missions);
        ReflectionTestUtils.setField(permits, "orderRepository", orders);
        when(missions.findByIdWithOrder("rework")).thenReturn(Optional.of(mission));
        var request = new com.ondemandmonitoring.mission.dto.request.FlightPermitUpdateRequest(
                FlightPermitStatus.APPROVED, null, null, null, null);
        assertThatThrownBy(() -> permits.updateFlightPermit("rework", request))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
    }

    @Test
    void uncoveredCaseItemsCannotFinalize() {
        item.setAssignedReworkMissionId(null);
        assertThatThrownBy(() -> service.finalizeReworkPlan("case", new ExecutionPlanFinalizeRequest(7L, true)))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
        verifyNoInteractions(materialization);
    }

    @Test
    void aStalePlanCannotFinalize() {
        assertThatThrownBy(() -> service.finalizeReworkPlan("case", new ExecutionPlanFinalizeRequest(6L, true)))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
        verifyNoInteractions(materialization);
    }

    @Test void automaticReworkDraftIsUnscheduledAndReplayDoesNotDuplicateIt() {
        var checklist = new OrderChecklistItem(); checklist.setId("checklist"); checklist.setContent("Inspect fence");
        item.setOrderChecklistItem(checklist); item.setAssignedReworkMissionId(null); item.setStatus(ResultRevisionCaseItemStatus.IN_PROGRESS);
        when(missions.findAllByOrderIdForUpdateOrderById("order")).thenReturn(List.of());
        var selector = mock(com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService.class);
        var snapshots = mock(com.ondemandmonitoring.mission.service.IMissionReworkContextSnapshotService.class);
        ReflectionTestUtils.setField(service, "checklistAssignmentService", selector);
        ReflectionTestUtils.setField(service, "reworkContextSnapshots", snapshots);
        when(missions.saveAndFlush(any())).thenAnswer(call -> {
            Mission created = call.getArgument(0); created.setId("auto-draft");
            when(missions.findById("auto-draft")).thenReturn(Optional.of(created));
            return created;
        });
        service.ensureResultRevisionReworkDrafts("case", false);
        var draft = org.mockito.ArgumentCaptor.forClass(Mission.class);
        verify(missions).saveAndFlush(draft.capture());
        assertThat(draft.getValue().getScheduledStartAt()).isNull();
        assertThat(draft.getValue().getScheduledEndAt()).isNull();
        assertThat(item.getAssignedReworkMissionId()).isEqualTo("auto-draft");
        service.ensureResultRevisionReworkDrafts("case", false);
        verify(missions, times(1)).saveAndFlush(any());
        verify(snapshots).capture(eq(draft.getValue()), eq(List.of(item)));
        verify(selector).replace(eq("auto-draft"), any());
    }

    @Test void newReworkItemsJoinTheExistingPlanningDraftAndPreserveEarlierScope() {
        var firstChecklist = new OrderChecklistItem(); firstChecklist.setId("first");
        item.setOrderChecklistItem(firstChecklist);
        var secondChecklist = new OrderChecklistItem(); secondChecklist.setId("second");
        var added = new ResultRevisionCaseItem(); added.setOrderChecklistItem(secondChecklist);
        added.setScopeDecision(ResultRevisionScope.IN_SCOPE); added.setStrategy(ResultRevisionStrategy.REWORK);
        added.setStatus(ResultRevisionCaseItemStatus.IN_PROGRESS);
        item.setStatus(ResultRevisionCaseItemStatus.IN_PROGRESS);
        when(caseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc("case")).thenReturn(List.of(item, added));
        var selector = mock(com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService.class);
        var snapshots = mock(com.ondemandmonitoring.mission.service.IMissionReworkContextSnapshotService.class);
        ReflectionTestUtils.setField(service, "checklistAssignmentService", selector);
        ReflectionTestUtils.setField(service, "reworkContextSnapshots", snapshots);
        service.ensureResultRevisionReworkDrafts("case", false);
        assertThat(added.getAssignedReworkMissionId()).isEqualTo("rework");
        verify(missions, never()).saveAndFlush(any());
        verify(selector).replace(eq("rework"), argThat(command ->
                command.items().stream().map(i -> i.orderChecklistItemId()).toList().equals(List.of("first", "second"))));
        verify(snapshots).capture(mission, List.of(added));
    }

    @Test void unscheduledReworkDraftCannotBeFinalized() {
        mission.setScheduledStartAt(null); mission.setScheduledEndAt(null);
        assertThatThrownBy(() -> service.finalizeReworkPlan("case", new ExecutionPlanFinalizeRequest(7L, false)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(materialization);
    }
}
