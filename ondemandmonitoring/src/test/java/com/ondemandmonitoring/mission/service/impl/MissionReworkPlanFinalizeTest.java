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
        ReflectionTestUtils.setField(service, "checklistMaterializationService", materialization);
        ReflectionTestUtils.setField(service, "missionMapper", mock(MissionMapper.class));
        order.setId("order"); order.setOrderStatus(OrderStatus.COMPLETED);
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED); order.setExecutionPlanRevision(7);
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
        when(payments.isOriginalFinalPaymentConfirmed(invoice)).thenReturn(true);
        when(payments.isResourceAssignmentReady(mission)).thenReturn(true);
    }

    @Test
    void finalizesReworkUsingSharedMaterializationAndResourceActivationWithoutReopeningOriginalPlan() {
        service.finalizeReworkPlan("case", new ExecutionPlanFinalizeRequest(7L, true));
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.PENDING_CONFIRMATION);
        assertThat(order.getExecutionPlanStatus()).isEqualTo(ExecutionPlanStatus.FINALIZED);
        assertThat(staff).allSatisfy(row -> assertThat(row.getResponseStatus()).isEqualTo(MissionStaffAssignmentStatus.PENDING));
        assertThat(device.getCheckupStatus()).isEqualTo(MissionDeviceCheckStatus.PENDING);
        verify(materialization).materialize(mission);
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
}
