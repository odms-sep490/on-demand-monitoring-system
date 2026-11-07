package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.dto.request.MissionCreateRequest;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import java.time.*;
import java.util.*;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionCreationServiceTest {
    MissionServiceTest fixture;
    Order order;
    static MissionCreateRequest request(String orderId, String key, long revision) {
        var r = new MissionCreateRequest(); r.setScopeLabel("Whole Order scope");
        r.setScheduledStartAt(Instant.parse("2026-10-08T02:00:00Z"));
        r.setScheduledEndAt(Instant.parse("2026-10-08T04:00:00Z")); return r;
    }
    @BeforeEach void setup() {
        fixture = new MissionServiceTest(); fixture.setUp();
        var quote = new com.ondemandmonitoring.finance.domain.Quote();
        quote.setStatus(com.ondemandmonitoring.finance.enums.QuoteStatus.ACCEPTED_BY_CUSTOMER);
        var invoice = new com.ondemandmonitoring.finance.domain.Invoice(); invoice.setQuote(quote);
        when(fixture.invoiceRepository.findByOrderId("o")).thenReturn(Optional.of(invoice));
        when(fixture.missionPaymentEligibilityService.isDepositSatisfied(invoice)).thenReturn(true);
        order = Order.builder().orderStatus(OrderStatus.APPROVED).preferredDateFrom(LocalDate.of(2026,10,8))
                .preferredDateTo(LocalDate.of(2026,10,8)).preferredTime(
                    com.ondemandmonitoring.warehouse.domain.PreferredTime.builder()
                        .startTime(LocalTime.of(9,0)).endTime(LocalTime.of(11,0)).build()).build(); order.setId("o");
        var geometry = new GeometryFactory(new PrecisionModel(),4326);
        order.setPoint(geometry.createPoint(new Coordinate(106,10)));
        when(fixture.orderRepository.findByIdForUpdate("o")).thenReturn(Optional.of(order));
        when(fixture.missionRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Mission m = inv.getArgument(0); m.setId(UUID.randomUUID().toString()); return m;
        });
    }
    MissionCreateRequest request() { return request("o",UUID.randomUUID().toString(),0); }
    void error(ErrorCode code, Runnable action) { assertEquals(code,assertThrows(ApiException.class,action::run).getErrorCode()); }
    @Test void newCreationCopiesScopeAndIncrementsOnlyPublicPlanRevision() {
        var r=request(); order.setVersion(7L);
        var created = fixture.missionService.createMission("o", r);
        assertNotNull(created); assertEquals(1, order.getExecutionPlan().getRevision()); assertEquals(7L, order.getVersion());
        var captor=org.mockito.ArgumentCaptor.forClass(Mission.class);
        verify(fixture.missionRepository).saveAndFlush(captor.capture());
        var m=captor.getValue(); assertEquals(MissionStatus.PLANNING, m.getStatus());
    }
    @Test void acceptedQuoteWithoutDepositBlocksMissionCreation() {
        when(fixture.missionPaymentEligibilityService.isDepositSatisfied(any())).thenReturn(false);
        error(ErrorCode.INVOICE_NOT_PAID, () -> fixture.missionService.createMission("o", request()));
        verify(fixture.missionRepository, never()).saveAndFlush(any());
    }
    @Test void missingAcceptedInvoiceBlocksCreationWithoutSavingMission() {
        when(fixture.invoiceRepository.findByOrderId("o")).thenReturn(Optional.empty());
        error(ErrorCode.INVOICE_NOT_PAYABLE, () -> fixture.missionService.createMission("o", request()));
        verify(fixture.missionRepository, never()).saveAndFlush(any());
    }
    @Test void finalizedPlanRejectsNewKey() {order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED); error(ErrorCode.EXECUTION_PLAN_NOT_DRAFT,()->fixture.missionService.createMission("o", request()));}
    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"COMPLETED","CANCELLED","REJECTED","IN_PROGRESS","PENDING"})
    void nonApprovedOrderRejectsNewKey(OrderStatus state) {order.setOrderStatus(state); error(ErrorCode.INVALID_REQUEST,()->fixture.missionService.createMission("o", request()));}
    @Test void validatesScheduleBoundsAndSingleDay() {
        // start >= end
        var r = request(); r.setScheduledEndAt(r.getScheduledStartAt()); var empty = r;
        error(ErrorCode.INVALID_REQUEST, () -> fixture.missionService.createMission("o", empty));

        // Different days
        r = request(); r.setScheduledEndAt(Instant.parse("2026-10-09T03:00:00Z")); var diffDays = r;
        error(ErrorCode.INVALID_REQUEST, () -> fixture.missionService.createMission("o", diffDays));

        // Before preferred date
        r = request(); r.setScheduledStartAt(Instant.parse("2026-10-07T02:00:00Z"));
        r.setScheduledEndAt(Instant.parse("2026-10-07T04:00:00Z")); var earlyDate = r;
        error(ErrorCode.INVALID_REQUEST, () -> fixture.missionService.createMission("o", earlyDate));

        // Outside preferred time slot (09:00 - 11:00 UTC+7 => 02:00Z - 04:00Z)
        r = request(); r.setScheduledStartAt(Instant.parse("2026-10-08T01:59:59Z")); var earlySlot = r;
        error(ErrorCode.INVALID_REQUEST, () -> fixture.missionService.createMission("o", earlySlot));

        r = request(); r.setScheduledEndAt(Instant.parse("2026-10-08T04:00:01Z")); var lateSlot = r;
        error(ErrorCode.INVALID_REQUEST, () -> fixture.missionService.createMission("o", lateSlot));
    }
    @Test void planningMissionCannotEnterLifecycleOrTelemetryEvenWithForcedRuntimeState() {
        var mission=new Mission(); mission.setId("m");
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.IN_FLIGHT);
        when(fixture.missionRepository.findById("m")).thenReturn(Optional.of(mission));
        error(ErrorCode.CHECKLIST_ASSIGNMENT_NOT_FINALIZED,()->fixture.deviceConnectionService.connectGcs("m"));
        error(ErrorCode.CHECKLIST_ASSIGNMENT_NOT_FINALIZED,()->fixture.missionService.completeMission("m"));
        verifyNoInteractions(fixture.missionDeviceAssignmentRepository,fixture.missionStaffAssignmentRepository);
    }
    @Test void crewMayReadTheirPlanningMissionButCannotGainOperationalPermissions() {
        var mission=new Mission();mission.setId("m");
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.IN_FLIGHT);
        var caller=com.ondemandmonitoring.user.domain.User.builder().isActive(true).role(com.ondemandmonitoring.role.domain.Role.builder().code(RoleCode.STAFF).active(true).build()).build();caller.setId("crew");
        when(fixture.authenticatedUserResolver.getCurrentUser()).thenReturn(caller);
        when(fixture.missionRepository.findById("m")).thenReturn(Optional.of(mission));
        when(fixture.missionStaffAssignmentRepository.existsByMissionIdAndStaffId("m","crew")).thenReturn(true);
        var auth=new com.ondemandmonitoring.mission.service.impl.MissionAuthorizationServiceImpl(fixture.missionStaffAssignmentRepository,fixture.missionRepository,fixture.authenticatedUserResolver,mock(com.ondemandmonitoring.mission.repository.MissionResultRepository.class), ScopeTestFixture.validScope(), mock(com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository.class));
        assertTrue(auth.canViewMission("m"));assertFalse(auth.canControlFlight("m"));assertFalse(auth.canOperatePayload("m"));
        assertFalse(auth.canExecuteMonitoringChecklist("m"));assertFalse(auth.canUploadMissionMedia("m"));
        assertFalse(auth.canInspectDevice("m"));assertFalse(auth.canMaintainDevice("m"));assertFalse(auth.canAttachChecklistEvidence("m"));
    }
    @Test void forgedPlanningResultCannotApproveOrCompleteOrder() {
        var mission=new Mission();mission.setId("m");mission.setOrder(order);
        mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.COMPLETED);
        var result=new com.ondemandmonitoring.mission.domain.MissionResult();result.setMission(mission);result.setApprovalStatus(com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        var results=mock(com.ondemandmonitoring.mission.repository.MissionResultRepository.class);
        when(results.findOrderIdByResultId("r")).thenReturn(Optional.of(order.getId()));when(results.findById("r")).thenReturn(Optional.of(result));
        when(fixture.orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(fixture.missionRepository.findIdsByOrderIdOrderById(order.getId())).thenReturn(List.of("m"));
        when(fixture.missionRepository.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        var checks=mock(IMissionChecklistExecutionService.class);
        var service=new com.ondemandmonitoring.mission.service.impl.MissionResultService(results,fixture.missionRepository,null,null,null,
                new com.ondemandmonitoring.order.service.impl.OrderExecutionCompletionPolicy(fixture.orderRepository,fixture.missionRepository,results,checks,
                        mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.class),
                        mock(com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository.class),
                        new com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService()),
                checks,fixture.authenticatedUserResolver,null);
        error(ErrorCode.CHECKLIST_ASSIGNMENT_NOT_FINALIZED,()->service.approve("r",null));
        assertEquals(OrderStatus.APPROVED,order.getOrderStatus());verifyNoInteractions(checks);verify(fixture.orderRepository,never()).save(any());
    }
}
