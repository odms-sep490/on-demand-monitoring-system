package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.order.enums.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real transactions/locks/constraints in the opt-in disposable PostgreSQL fixture. */
@SpringJUnitConfig(MissionChecklistCutoverPostgresTest.Beans.class)
class MissionChecklistCutoverPostgresTest extends ChecklistPostgresTest {
    @Autowired MissionRepository missions;
    @Autowired IMissionService missionService;
    @Autowired IMissionChecklistAssignmentService selection;
    @Autowired IExecutionPlanService plan;
    @Autowired IMissionChecklistScopeService scope;
    @Autowired IMissionChecklistExecutionService runtime;
    @Autowired IChecklistEvidenceService evidence;
    @Autowired IMissionAuthorizationService auth;
    @Autowired MissionChecklistExecutionRepository executions;
    @Autowired MissionChecklistAssignmentRepository missionAssignments;
    @Autowired IMissionChecklistMaterializationService materializer;
    JdbcTemplate jdbc;
    String orderId, first, second;
    List<String> itemIds;

    @Override @BeforeEach void prepare() {
        jdbc = new JdbcTemplate(dataSource); jdbc.execute("TRUNCATE missions CASCADE");
        super.prepare();
    }
    void fixture(int count) {
        String serviceId = service("Monitoring");
        for (int i = 0; i < count; i++) assignments.assign(serviceId, assignment(checklist("Requirement " + i), i));
        orderId = orderService.createOrder(orderRequest(serviceId)).getId();
        transaction.executeWithoutResult(tx -> orders.findById(orderId).orElseThrow().setOrderStatus(OrderStatus.APPROVED));
        String actorId = resolver.getCurrentUser().getId();
        when(resolver.getCurrentUserId()).thenReturn(actorId);
        when(auth.canAttachChecklistEvidence(anyString())).thenReturn(true);
        itemIds = transaction.execute(tx -> snapshots.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of(orderId)).stream().map(i -> i.getId()).toList());
        first = create(); second = create();
    }
    String create() {
        var r = new MissionCreateRequest();
        r.setScopeLabel("Monitoring scope");
        var start = java.time.LocalDate.now().plusDays(7).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        r.setScheduledStartAt(start); r.setScheduledEndAt(start.plusSeconds(3600));
        return missionService.createMission(orderId, r).getId();
    }
    long revision() { return jdbc.queryForObject("select execution_plan_revision from orders where id=?", Long.class, orderId); }
    ChecklistAssignmentSelectionRequest request(long revision, int... indices) {
        return new ChecklistAssignmentSelectionRequest(revision, Arrays.stream(indices).mapToObj(i -> new ChecklistAssignmentSelectionRequest.Item(itemIds.get(i))).toList());
    }
    void choose(String mission, int... indices) { selection.replace(mission, request(revision(), indices)); }
    void finalizePlan() { plan.finalizePlan(orderId, new ExecutionPlanFinalizeRequest(revision(), true)); }
    long rows(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    String asset(String mission) {
        String id=UUID.randomUUID().toString(),device=UUID.randomUUID().toString(),model=UUID.randomUUID().toString();
        jdbc.update("insert into device_models(id,created_at,version,code,model_name) values (?,now(),0,?,'Test model')",model,model);
        jdbc.update("insert into devices(id,created_at,version,serial_number,name,model_id,status) values (?,now(),0,?,'Test camera',?,'AVAILABLE')",device,device,model);
        String deviceRole = jdbc.queryForObject("select count(*) from mission_device_assignments where mission_id=? and is_current and device_role='MAIN'", Integer.class, mission) == 0 ? "MAIN" : "SUPPORT";
        jdbc.update("insert into mission_device_assignments(id,created_at,version,mission_id,device_id,device_role,checkup_status,is_current) values (?,now(),0,?,?,?,'PENDING',true)",UUID.randomUUID().toString(),mission,device,deviceRole);
        jdbc.update("insert into drone_media(id,created_at,version,mission_id,device_id,media_type,storage_provider,original_file_name,content_type,file_size,s3_bucket,s3_key,s3_url,captured_at,media_status,source_type,validated_at,preview_selected,storage_status) values (?,now(),0,?,?,'IMAGE','S3','capture.jpg','image/jpeg',20,'test',?,?,now(),'PENDING_MANAGER_APPROVAL','DRONE_CAMERA',now(),false,'AVAILABLE')",id,mission,device,id,"s3://test/"+id);
        return id;
    }
    void error(ErrorCode code, Runnable action) { assertEquals(code, assertThrows(ApiException.class, action::run).getErrorCode()); }

    @Test void repeatedItemCreatesSixIndependentExecutionsAndMissionSpecificReadiness() {
        fixture(5); choose(first,0,2,4); choose(second,1,3,4);
        assertEquals(0, rows("mission_checklist_executions"));
        var before = plan.get(orderId); assertTrue(before.coverage().coverageReady()); assertEquals(1, before.coverage().repeated());
        long consumed = revision(); finalizePlan();
        assertEquals(6, rows("mission_checklist_executions")); assertEquals(consumed + 1, revision());
        jdbc.update("update mission_checklist_executions set execution_status='COMPLETED',assessment_status='NON_COMPLIANT',started_at=now(),completed_at=now() where mission_id=?", first);
        jdbc.update("update missions set status='PENDING_REVIEW' where id=?",first);
        var firstRows=runtime.getByMissionId(first).executions();
        assertFalse(runtime.getByMissionId(first).readyForSubmission()); // V1 still needs evidence.
        evidence.attach(first,new BatchChecklistEvidenceRequest(asset(first),firstRows.stream()
                .map(r->new BatchChecklistEvidenceRequest.Target(r.getId(),r.getVersion())).toList(),null));
        transaction.executeWithoutResult(tx -> {
            assertTrue(runtime.isReadyForSubmission(missions.findById(first).orElseThrow()));
            assertFalse(runtime.isReadyForSubmission(missions.findById(second).orElseThrow()));
            assertTrue(scope.isMaterialized(missions.findById(first).orElseThrow()));
        });
        assertEquals(2, jdbc.queryForObject("select count(*) from mission_checklist_executions where order_checklist_item_id=?", Integer.class,itemIds.get(4)));
        plan.finalizePlan(orderId, new ExecutionPlanFinalizeRequest(consumed,true));
        assertEquals(6, rows("mission_checklist_executions")); assertEquals(consumed+1, revision());
        error(ErrorCode.EXECUTION_PLAN_NOT_DRAFT, () -> choose(first,0));
    }
    @Test void coverageMissingRemovedAndCancelledDoNotCount() {
        fixture(2); choose(first,0); choose(second,1);
        choose(second); assertFalse(plan.get(orderId).coverage().coverageReady());
        assertEquals(1, rows("mission_checklist_assignments") - selection.get(first).assignments().size());
        error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE,this::finalizePlan);
        choose(second,1); jdbc.update("update missions set status='CANCELLED',cancellation_at=now() where id=?", second);
        assertFalse(plan.get(orderId).coverage().coverageReady()); error(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE,this::finalizePlan);
        assertEquals(0, rows("mission_checklist_executions"));
    }
    @Test void emptyOrderFinalizesWithoutFabricatingRows() {
        fixture(0); finalizePlan();
        assertTrue(plan.get(orderId).coverage().coverageReady()); assertEquals(0, rows("mission_checklist_executions"));
        transaction.executeWithoutResult(tx -> assertTrue(runtime.isReadyForSubmission(missions.findById(first).orElseThrow())));
    }
    @Test void assignmentsPreserveHistoryNormalizeOrderAndRejectDuplicateStaleCrossOrder() {
        fixture(3); choose(first,2,0); var original = selection.get(first).assignments();
        assertEquals(List.of(0,1),original.stream().map(a -> a.displayOrder()).toList());
        choose(first,0); choose(first,2,0);
        assertEquals(3, rows("mission_checklist_assignments"));
        assertNotEquals(original.getFirst().assignmentId(),selection.get(first).assignments().getFirst().assignmentId());
        error(ErrorCode.CHECKLIST_ASSIGNMENT_DUPLICATE, () -> choose(first,0,0));
        error(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT, () -> selection.replace(first,request(0,1)));
        error(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER, () -> selection.replace(first,
                new ChecklistAssignmentSelectionRequest(revision(),List.of(new ChecklistAssignmentSelectionRequest.Item("nonexistent")))));
    }
    @Test void crossOrderAssignmentFailsBeforeAnyMaterializationAndLeavesAuditUntouched() {
        fixture(1); choose(first,0);
        // Database composite FKs reject a forged cross-Order assignment even when APIs are bypassed.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
            insert into mission_checklist_assignments(id,mission_id,order_id,order_checklist_item_id,display_order,assigned_at,created_at,version)
            values('forged',?,'different-order',?,0,now(),now(),0)
            """,second,itemIds.getFirst()));
        assertEquals(0, rows("mission_checklist_executions")); assertEquals("DRAFT",plan.get(orderId).planStatus().name());
    }
    @Test void failureOnSecondMaterializationRollsBackFirstAndAllFinalizationMetadata() {
        fixture(2); choose(first,0); choose(second,1);
        var sorted = List.of(first,second).stream().sorted().toList();
        String constraint = "ck_force_materialization_failure";
        jdbc.execute("alter table mission_checklist_executions add constraint " + constraint + " check(mission_id <> '" + sorted.getLast() + "')");
        long before = revision();
        try { error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID,this::finalizePlan); }
        finally { jdbc.execute("alter table mission_checklist_executions drop constraint " + constraint); }
        assertEquals(before,revision()); assertEquals(0,rows("mission_checklist_executions"));
        assertEquals(0,jdbc.queryForObject("select count(*) from missions where finalized_plan_revision is not null",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from orders where execution_plan_finalized_at is not null or execution_plan_finalized_by is not null",Integer.class));
    }
    @Test void concurrentAssignmentCommandsHaveOneWinnerAndOneStaleRevision() throws Exception {
        fixture(2); var command = request(revision(),0,1);
        var outcomes = race(() -> selection.replace(first,command));
        assertEquals(1,outcomes.stream().filter(o -> !(o instanceof Throwable)).count());
        assertEquals(1,outcomes.stream().filter(o -> o instanceof ApiException e && e.getErrorCode()==ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT).count());
        assertEquals(2,rows("mission_checklist_assignments")); assertEquals(3,revision());
    }
    @Test void concurrentFinalizeHasOneConsistentCommitAndSafeReplay() throws Exception {
        fixture(1); choose(first,0); choose(second,0);
        var command = new ExecutionPlanFinalizeRequest(revision(),true);
        var outcomes = race(() -> plan.finalizePlan(orderId,command));
        assertTrue(outcomes.stream().noneMatch(o -> o instanceof Throwable),outcomes.toString());
        assertEquals(2,rows("mission_checklist_executions")); assertEquals(command.expectedPlanRevision()+1,revision());
    }
    @Test void databaseRejectsDuplicateActiveSelectionAndNullExecutionAssignment() {
        fixture(1); choose(first,0);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("""
            insert into mission_checklist_assignments(id,mission_id,order_id,order_checklist_item_id,display_order,assigned_at,created_at,version)
            select 'duplicate',mission_id,order_id,order_checklist_item_id,display_order,assigned_at,created_at,version from mission_checklist_assignments
            """));
        finalizePlan();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("update mission_checklist_executions set mission_checklist_assignment_id=null"));
    }
    @Test void runtimeGateRejectsPlanningAndMissingOrRemovedAssignments() {
        fixture(1); choose(first,0);
        error(ErrorCode.CHECKLIST_ASSIGNMENT_NOT_FINALIZED, () -> transaction.executeWithoutResult(tx -> scope.requireMaterialized(missions.findById(first).orElseThrow())));
        finalizePlan();
        jdbc.update("update mission_checklist_assignments set removed_at=now(),removed_by='manager' where mission_id=?",first);
        transaction.executeWithoutResult(tx -> assertFalse(scope.isMaterialized(missions.findById(first).orElseThrow())));
    }

    @Test void sameSnapshotEvidenceCannotLeakBetweenSiblingExecutions() {
        fixture(1); choose(first,0); choose(second,0); finalizePlan();
        jdbc.update("update missions set status='PENDING_REVIEW'");
        String media = asset(first);
        var a=runtime.getByMissionId(first).executions().getFirst();
        var b=runtime.getByMissionId(second).executions().getFirst();
        assertNotEquals(a.getId(),b.getId());
        evidence.attach(first,new BatchChecklistEvidenceRequest(media,List.of(new BatchChecklistEvidenceRequest.Target(a.getId(),a.getVersion())),null));
        error(ErrorCode.ACCESS_DENIED,()->evidence.attach(second,new BatchChecklistEvidenceRequest(media,List.of(new BatchChecklistEvidenceRequest.Target(b.getId(),b.getVersion())),null)));
        assertEquals(0,runtime.getByMissionId(second).executions().getFirst().getEvidence().size());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("""
            insert into mission_checklist_evidence(id,created_at,version,mission_id,mission_checklist_execution_id,media_asset_id,attached_at,attached_by)
            values('forged-link',now(),0,?,?,?,now(),?)
            """,second,b.getId(),media,resolver.getCurrentUserId()));
    }

    @Configuration @Import(OrderMissionPostgresTest.MissionConfig.class)
    static class Beans {
        @Bean IMissionChecklistMaterializationService materializer(MissionChecklistAssignmentRepository a,MissionChecklistExecutionRepository e) {
            return new MissionChecklistMaterializationServiceImpl(a,e);
        }
        @Bean IMissionChecklistAssignmentService selection(com.ondemandmonitoring.order.repository.OrderRepository orders,MissionRepository missions,
                com.ondemandmonitoring.order.repository.OrderChecklistItemRepository items,MissionChecklistAssignmentRepository a,
                MissionChecklistExecutionRepository e,com.ondemandmonitoring.user.service.AuthenticatedUserResolver user) {
            return new MissionChecklistAssignmentServiceImpl(orders,missions,items,a,e,user,
                    org.mapstruct.factory.Mappers.getMapper(com.ondemandmonitoring.mission.mapper.MissionChecklistAssignmentMapper.class));
        }
        @Bean IExecutionPlanService plan(com.ondemandmonitoring.order.repository.OrderRepository orders,MissionRepository missions,
                com.ondemandmonitoring.order.repository.OrderChecklistItemRepository items,MissionChecklistAssignmentRepository a,
                IMissionChecklistMaterializationService materializer,IMissionChecklistScopeService scope,
                com.ondemandmonitoring.user.service.AuthenticatedUserResolver user,MissionResultRepository results,IMissionChecklistExecutionService checklist,
                MissionStaffAssignmentRepository staff, MissionDeviceAssignmentRepository devices) {
            return new ExecutionPlanServiceImpl(orders,missions,items,a,materializer,scope,user,new ExecutionPlanCoverageService(),
                    staff, devices);
        }
    }
}
