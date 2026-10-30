package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Historical filename retained; verifies finalized-plan extensions without the removed amend service.
 * Checklist persistence and transactions use PostgreSQL; resource availability is stubbed.
 */
@EnabledIfEnvironmentVariable(named = "ODMS_CHECKLIST_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/odms_checklist_verification")
@SpringJUnitConfig(ExecutionPlanAmendmentPostgresTest.Beans.class)
class ExecutionPlanAmendmentPostgresTest extends OrderAggregateCompletionPostgresTest {
    @Autowired IMissionCancellationService cancellation;
    @Autowired MissionStaffAssignmentRepository staff;
    @Autowired MissionDeviceAssignmentRepository devices;

    @Override @BeforeEach void prepare() {
        super.prepare();
        jdbc = new JdbcTemplate(dataSource);
        reset(staff, devices);
    }

    void fixture() {
        var serviceId = service("Monitoring");
        for (int i = 0; i < 2; i++) assignments.assign(serviceId, assignment(checklist("Requirement " + i), i));
        orderId = orderService.createOrder(orderRequest(serviceId)).getId();
        transaction.executeWithoutResult(tx -> orders.findById(orderId).orElseThrow().setOrderStatus(OrderStatus.APPROVED));
        when(resolver.getCurrentUserId()).thenReturn(resolver.getCurrentUser().getId());
        itemIds = snapshots.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of(orderId)).stream().map(i -> i.getId()).toList();
        first = missionService.createMission(orderId, request("First scope", 0)).getId();
        second = missionService.createMission(orderId, request("Second scope", 1)).getId();
        resources(first); resources(second);
        plan.finalizePlan(orderId, new ExecutionPlanFinalizeRequest(revision(), true));
    }

    MissionCreateRequest request(String label, int index) {
        var request = new MissionCreateRequest(); request.setScopeLabel(label);
        var start = LocalDate.now().plusDays(7).atTime(8, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        request.setScheduledStartAt(start); request.setScheduledEndAt(start.plusSeconds(3600));
        request.setChecklistIds(List.of(itemIds.get(index))); return request;
    }

    void resources(String missionId) {
        when(staff.findAllByMissionIdAndIsCurrentTrue(missionId)).thenReturn(Arrays.stream(MissionStaffRole.values())
                .map(role -> MissionStaffAssignment.builder().staff(new User()).assignedRole(role)
                        .responseStatus(MissionStaffAssignmentStatus.PENDING).build()).toList());
        when(devices.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId)).thenReturn(List.of(
                MissionDeviceAssignment.builder().device(new Device()).deviceRole(DeviceRole.MAIN).isCurrent(true).build()));
    }

    long revision() { return jdbc.queryForObject("select execution_plan_revision from orders where id=?", Long.class, orderId); }
    String state(String mission) { return jdbc.queryForObject("select status from missions where id=?", String.class, mission); }
    String planStatus() { return jdbc.queryForObject("select execution_plan_status from orders where id=?", String.class, orderId); }
    void cancel(String mission) { cancellation.cancel(mission, new MissionCancelRequest(revision(), "  Weather requires another flight  ")); }
    void error(ErrorCode code, Runnable action) { assertEquals(code, assertThrows(ApiException.class, action::run).getErrorCode()); }
    Map<String, Object> history(String mission) {
        var data = new LinkedHashMap<String, Object>();
        for (String table : List.of("mission_checklist_assignments", "mission_checklist_executions", "mission_checklist_evidence", "drone_media", "mission_results"))
            data.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb)::text from " + table + " t where mission_id=?", String.class, mission));
        return data;
    }

    @Test void additionalMissionRetainsFinalizationAuditAndExistingHistory() {
        fixture();
        var audit = jdbc.queryForMap("select execution_plan_finalized_at,execution_plan_finalized_by from orders where id=?", orderId);
        var originalHistory = history(first); var siblingHistory = history(second);
        cancel(first); long before = revision();
        var third = missionService.createAdditionalMission(orderId, request("Additional scope", 0)).getId();
        assertEquals("FINALIZED", planStatus()); assertEquals("PLANNING", state(third));
        assertEquals(before + 1, revision());
        assertEquals(originalHistory, history(first)); assertEquals(siblingHistory, history(second));
        assertEquals(audit, jdbc.queryForMap("select execution_plan_finalized_at,execution_plan_finalized_by from orders where id=?", orderId));
        assertEquals(0, jdbc.queryForObject("select count(*) from mission_checklist_executions where mission_id=?", Integer.class, third));
    }

    @Test void individualConfirmationMaterializesOnlyTheNewMission() {
        fixture(); cancel(first);
        var siblingHistory = history(second);
        var third = missionService.createAdditionalMission(orderId, request("Additional scope", 0)).getId();
        error(ErrorCode.INVALID_REQUEST, () -> missionService.confirmMissionPlanning(third));
        assertEquals("PLANNING", state(third));
        resources(third); long before = revision();
        missionService.confirmMissionPlanning(third);
        assertEquals("READY", state(third)); assertEquals("FINALIZED", planStatus());
        assertEquals(before + 1, revision()); assertEquals(siblingHistory, history(second));
        assertEquals(1, jdbc.queryForObject("select count(*) from mission_checklist_executions where mission_id=?", Integer.class, third));
        error(ErrorCode.MISSION_STATUS_INVALID, () -> missionService.confirmMissionPlanning(third));
    }

    @Test void cancellationDoesNotRequireSafeStopAndPreservesExecutionHistory() {
        fixture(); var before = history(first);
        jdbc.update("update missions set status='IN_FLIGHT' where id=?", first);
        cancel(first);
        assertEquals("CANCELLED", state(first)); assertEquals(before, history(first));
        assertNull(jdbc.queryForObject("select safe_stop_status from missions where id=?", String.class, first));
        assertEquals("Weather requires another flight", jdbc.queryForObject("select cancellation_reason from missions where id=?", String.class, first));
    }

    @Test void cancellationChecksRevisionFinalizedStatusAndReplay() {
        fixture(); long before = revision();
        error(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT, () -> cancellation.cancel(first, new MissionCancelRequest(before - 1, "Reason")));
        cancel(first);
        var audit = jdbc.queryForMap("select cancellation_at,cancelled_by,cancellation_reason,version from missions where id=?", first);
        error(ErrorCode.MISSION_ALREADY_CANCELLED, () -> cancel(first));
        // Deliberately inconsistent fixture verifies the guard; there is no amend command.
        jdbc.update("update orders set execution_plan_status='DRAFT' where id=?", orderId);
        error(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED, () -> cancel(second));
        assertEquals(before + 1, revision());
        assertEquals(audit, jdbc.queryForMap("select cancellation_at,cancelled_by,cancellation_reason,version from missions where id=?", first));
    }

    @Test void additionalMissionCanBeEditedBeforeConfirmation() {
        fixture(); cancel(first);
        var third = missionService.createAdditionalMission(orderId, request("Additional scope", 0)).getId();
        var update = new MissionUpdateRequest(); update.setScheduledEndAt(request("Additional scope", 0).getScheduledEndAt().plusSeconds(60));
        missionService.updateMission(third, update);
        assertEquals(update.getScheduledEndAt(), transaction.execute(tx -> missions.findById(third).orElseThrow().getScheduledEndAt()));
        resources(third); missionService.confirmMissionPlanning(third);
        error(ErrorCode.MISSION_STATUS_INVALID, () -> missionService.updateMission(third, update));
        assertEquals("FINALIZED", planStatus());
    }

    @Test void additionalCreationDoesNotReopenOrRefinalizeOrderPlan() {
        fixture();
        error(ErrorCode.EXECUTION_PLAN_NOT_DRAFT, () -> plan.finalizePlan(orderId, new ExecutionPlanFinalizeRequest(revision(), true)));
        error(ErrorCode.CHECKLIST_ALREADY_ASSIGNED, () -> missionService.createAdditionalMission(orderId, request("Duplicate scope", 0)));
        assertEquals(2, jdbc.queryForObject("select count(*) from missions where order_id=?", Integer.class, orderId));
        assertEquals("FINALIZED", planStatus());
    }

    String foreignOrder() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into orders select (jsonb_populate_record(null::orders,to_jsonb(o)||jsonb_build_object('id',?,'order_code',?,'execution_plan_status','DRAFT','execution_plan_revision',0,'execution_plan_finalized_at',null,'execution_plan_finalized_by',null,'execution_plan_amended_at',null,'execution_plan_amended_by',null,'execution_plan_amendment_reason',null))).* from orders o where id=?", id,id,orderId);
        return id;
    }
    List<Object> race(Callable<?> one, Callable<?> two) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); try { return one.call(); } catch (ApiException e) { return e.getErrorCode(); } });
            var b = pool.submit(() -> { start.await(); try { return two.call(); } catch (ApiException e) { return e.getErrorCode(); } });
            start.countDown(); return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }

    @Configuration @Import(OrderAggregateCompletionPostgresTest.Beans.class)
    static class Beans {
        @Bean MissionStaffAssignmentRepository staff() { return mock(MissionStaffAssignmentRepository.class); }
        @Bean MissionDeviceAssignmentRepository devices() { return mock(MissionDeviceAssignmentRepository.class); }
        @Bean @Primary IMissionService planningMissionService(MissionRepository missions, OrderRepository orders,
                IMissionChecklistExecutionService executions, AuthenticatedUserResolver actor,
                IMissionChecklistAssignmentService selection, MissionStaffAssignmentRepository staff, MissionDeviceAssignmentRepository devices) {
            var mapper = mock(MissionMapper.class);
            when(mapper.toResponse(any())).thenAnswer(call -> {
                Mission mission = call.getArgument(0);
                return com.ondemandmonitoring.mission.dto.response.MissionResponse.builder().id(mission.getId())
                        .missionCode(mission.getMissionCode()).status(mission.getStatus()).build();
            });
            return new MissionService(missions, null, null, mapper, null, null, null, null, null, null,
                    orders, null, OrderMissionPostgresTest.paidInvoices(), OrderMissionPostgresTest.paidEligibility(),
                    null, null, null, null, null, null, actor,
                    mock(com.ondemandmonitoring.userschedule.repository.UserScheduleRepository.class), devices, staff,
                    mock(ResourceTimeLockRepository.class), executions, selection, mock(IResourceReservationService.class), null);
        }
        @Bean IMissionCancellationService cancellation(OrderRepository orders, MissionRepository missions,
                MissionResultRepository results, AuthenticatedUserResolver actor) {
            return new MissionCancellationServiceImpl(orders, missions, results, actor);
        }
    }
}
