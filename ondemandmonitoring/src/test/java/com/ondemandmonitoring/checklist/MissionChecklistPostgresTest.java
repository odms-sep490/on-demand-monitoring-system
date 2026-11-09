package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionResultMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real transactions, real PostgreSQL constraints; inherits Order/Mission snapshot regressions. */
@SpringJUnitConfig(MissionChecklistPostgresTest.ExecutionConfig.class)
class MissionChecklistPostgresTest extends OrderMissionPostgresTest {
    @Autowired IMissionChecklistExecutionService executionService;
    @Autowired MissionChecklistExecutionRepository executions;
    @Autowired MissionResultRepository resultRepository;
    @Autowired IMissionResultService resultService;
    @Autowired IMissionAuthorizationService auth;
    OrderCreateRequest cachedRequest;

    @Override @BeforeEach void prepare() {
        super.prepare(); cachedRequest = null;
        reset(auth);
        when(auth.canViewMission(anyString())).thenReturn(true);
        when(auth.canExecuteMonitoringChecklist(anyString())).thenReturn(true);
        when(auth.canReviewMissionMedia(anyString())).thenReturn(true);
        when(auth.canSubmitMissionResult(anyString())).thenReturn(true);
        when(resolver.getCurrentUserId()).thenReturn("authenticated-operator");
    }

    String snapshotOrder(int count) {
        if (cachedRequest == null) cachedRequest = orderRequest(service("Monitoring"));
        cachedRequest.setChecklistItems(java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> selected(null, "Historical requirement " + i)).toList());
        String id = orderService.createOrder(cachedRequest).getId();
        // These Phase 5 regressions explicitly exercise historical policy-0 packages.
        // Phase 6 service integration tests exercise configurable V2 snapshots.
        new JdbcTemplate(dataSource).update("update order_checklist_items set minimum_image_count=0,minimum_video_count=0,evidence_policy_version=0, minimum_evidence_count=0, required_evidence_type='IMAGE_OR_VIDEO' where order_id=?", id);
        transaction.executeWithoutResult(tx -> orders.findById(id).orElseThrow().setOrderStatus(OrderStatus.APPROVED));
        return id;
    }

    String mission(int count) {
        String order = snapshotOrder(count);
        String id = legacyMission(order, request(order)).getId();
        setStatus(id, MissionStatus.IN_FLIGHT); return id;
    }

    void setStatus(String id, MissionStatus status) {
        transaction.executeWithoutResult(tx -> {
            Mission mission = missions.findByIdForUpdate(id).orElseThrow(); mission.setStatus(status);
            if (status == MissionStatus.COMPLETED) mission.setCompletedAt(Instant.now());
        });
    }

    ChecklistExecutionUpdateRequest update(ChecklistExecutionResponse row, ChecklistExecutionStatus status) {
        var request = new ChecklistExecutionUpdateRequest(); request.setExpectedVersion(row.getVersion());
        request.setExecutionStatus(status); request.setAssessmentStatus(ChecklistAssessmentStatus.NOT_ASSESSED);
        if (status == ChecklistExecutionStatus.UNABLE_TO_VERIFY) request.setUnableToVerifyReason("  Visibility obstructed  ");
        return request;
    }

    ChecklistExecutionResponse change(String mission, ChecklistExecutionResponse row, ChecklistExecutionStatus status) {
        return executionService.update(mission, row.getId(), update(row, status));
    }

    void error(ErrorCode code, Runnable call) {
        assertEquals(code, assertThrows(ApiException.class, call::run).getErrorCode());
    }

    @Test void planningCreationDoesNotInitializeAcrossRetries() {
        String order = snapshotOrder(3);
        var controller = new com.ondemandmonitoring.mission.controller.MissionController(missionService, null, null);
        var first = controller.createMission(order, request(order)).getBody().getData();
        var second = controller.createMission(order, request(order)).getBody().getData();
        assertEquals(first.getId(), second.getId());
        assertNotEquals(first.getId(), missionService.createMission(order, request(order)).getId());
        var rows = executionService.getByMissionId(first.getId()).executions();
        assertTrue(rows.isEmpty());
    }

    @Test void explicitEmptyAndLegacyAreDistinctWithoutTemplateBackfill() {
        String explicit = mission(0);
        assertFalse(executionService.getByMissionId(explicit).legacySnapshot());
        assertTrue(executionService.getByMissionId(explicit).readyForSubmission());
        String order = snapshotOrder(0);
        transaction.executeWithoutResult(tx -> orders.findById(order).orElseThrow().setChecklistSnapshotAt(null));
        String legacy = legacyMission(order, request(order)).getId();
        assertTrue(executionService.getByMissionId(legacy).legacySnapshot());
        assertTrue(executionService.getByMissionId(legacy).readyForSubmission());
        assertTrue(executionService.getByMissionId(legacy).executions().isEmpty());
    }

    @Test void failedInitializationRollsBackMissionAndEveryExecution() {
        String order = snapshotOrder(3);
        String forbidden = snapshots.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of(order)).getLast().getId();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE mission_checklist_executions ADD CONSTRAINT fixture_reject_execution CHECK (order_checklist_item_id <> '" + forbidden + "')");
        try {
            error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, () -> legacyMission(order, request(order)));
            assertEquals(0, count(order)); assertEquals(0, executions.count());
            assertEquals(3, snapshots.count());
        } finally { jdbc.execute("ALTER TABLE mission_checklist_executions DROP CONSTRAINT fixture_reject_execution"); }
    }

    @Test void concurrentPlanningCreationProducesOneMissionWithoutExecutions() throws Exception {
        String order = snapshotOrder(3);
        List<Object> outcomes = race(() -> missionService.createMission(order, request(order)).getId());
        assertNotNull(outcomes.getFirst()); assertNotNull(outcomes.getLast());
        assertEquals(2, count(order)); assertEquals(0, executions.count());
    }

    @Test void historicalContentSurvivesCatalogChangesAndExecutionUpdates() {
        String service = service("Historical service"), catalogId = checklist("Original catalog requirement");
        assignments.assign(service, assignment(catalogId, 3));
        var request = orderRequest(service);
        String order = orderService.createOrder(request).getId();
        new JdbcTemplate(dataSource).update("update order_checklist_items set minimum_image_count=0,minimum_video_count=0,evidence_policy_version=0, minimum_evidence_count=0, required_evidence_type='IMAGE_OR_VIDEO' where order_id=?", order);
        transaction.executeWithoutResult(tx -> orders.findById(order).orElseThrow().setOrderStatus(OrderStatus.APPROVED));
        String mission = legacyMission(order, request(order)).getId(); setStatus(mission, MissionStatus.IN_FLIGHT);
        var row = executionService.getByMissionId(mission).executions().getFirst();
        long snapshotVersion = snapshots.findById(row.getOrderChecklistItemId()).orElseThrow().getVersion();
        var edit = new com.ondemandmonitoring.checklist.dto.request.ChecklistRequest(); edit.setContent("New template");
        catalog.update(catalogId, edit); catalog.updateStatus(catalogId, false); assignments.unassign(service, catalogId);
        var changed = change(mission, row, ChecklistExecutionStatus.COMPLETED);
        assertEquals(row.getContent(), changed.getContent()); assertEquals(row.getDisplayOrder(), changed.getDisplayOrder());
        assertEquals(row.getOrderChecklistItemId(), changed.getOrderChecklistItemId());
        var historical = snapshots.findById(row.getOrderChecklistItemId()).orElseThrow();
        assertEquals("Original catalog requirement", historical.getContent()); assertEquals(snapshotVersion, historical.getVersion());
        assertEquals(1L, changed.getVersion());
    }

    @Test void directSqlCannotInsertOrUpdateCrossOrderReferences() {
        String first = mission(1), second = mission(1);
        var firstRow = executionService.getByMissionId(first).executions().getFirst();
        var secondRow = executionService.getByMissionId(second).executions().getFirst();
        var jdbc = new JdbcTemplate(dataSource);
        String firstOrder = jdbc.queryForObject("select order_id from missions where id=?", String.class, first);
        String secondAssignment = jdbc.queryForObject("select mission_checklist_assignment_id from mission_checklist_executions where id=?", String.class, secondRow.getId());
        jdbc.update("delete from mission_checklist_executions where id=?", secondRow.getId());
        var insertion = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into mission_checklist_executions (id,created_at,version,mission_id,order_id,order_checklist_item_id,mission_checklist_assignment_id,execution_status,assessment_status) values (?,now(),0,?,?,?,?,'PENDING','NOT_ASSESSED')",
                UUID.randomUUID().toString(), first, firstOrder, secondRow.getOrderChecklistItemId(), secondAssignment));
        assertEquals("23503", ((java.sql.SQLException) insertion.getMostSpecificCause()).getSQLState());
        assertTrue(insertion.getMostSpecificCause().getMessage().contains("fk_execution_"));
        var modification = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update mission_checklist_executions set order_checklist_item_id=? where id=?",
                secondRow.getOrderChecklistItemId(), firstRow.getId()));
        assertEquals("23503", ((java.sql.SQLException) modification.getMostSpecificCause()).getSQLState());
        assertTrue(modification.getMostSpecificCause().getMessage().contains("fk_execution_"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update mission_checklist_executions set mission_id=? where id=?", second, firstRow.getId()));
        error(ErrorCode.CHECKLIST_EXECUTION_PARENT_MISMATCH,
                () -> executionService.update(second, firstRow.getId(), update(firstRow, ChecklistExecutionStatus.COMPLETED)));
    }

    @Test void sqlEnforcesNullabilityUniquenessAndExecutionShape() {
        String mission = mission(1); var row = executionService.getByMissionId(mission).executions().getFirst();
        var jdbc = new JdbcTemplate(dataSource);
        for (String column : List.of("mission_id", "order_id", "order_checklist_item_id", "mission_checklist_assignment_id", "execution_status", "assessment_status", "version")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "update mission_checklist_executions set " + column + "=null where id=?", row.getId()));
        }
        var duplicate = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into mission_checklist_executions (id,created_at,updated_at,version,mission_id,order_id,order_checklist_item_id,mission_checklist_assignment_id,execution_status,assessment_status,observation,unable_to_verify_reason,started_at,completed_at,last_modified_by) select 'duplicate',created_at,updated_at,version,mission_id,order_id,order_checklist_item_id,mission_checklist_assignment_id,execution_status,assessment_status,observation,unable_to_verify_reason,started_at,completed_at,last_modified_by from mission_checklist_executions"));
        assertEquals("23505", ((java.sql.SQLException) duplicate.getMostSpecificCause()).getSQLState());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update mission_checklist_executions set execution_status='UNABLE_TO_VERIFY' where id=?", row.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update mission_checklist_executions set assessment_status='INVALID' where id=?", row.getId()));
        for (String column : List.of("mission_id", "order_checklist_item_id")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "update mission_checklist_executions set " + column + "=? where id=?", UUID.randomUUID().toString(), row.getId()));
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update mission_checklist_executions set execution_status='UNABLE_TO_VERIFY',started_at=now(),completed_at=now(),unable_to_verify_reason='   ' where id=?", row.getId()));
    }

    @Test void realConcurrentUpdatesHaveExactlyOneWinnerAndOneConflict() throws Exception {
        String mission = mission(1); var row = executionService.getByMissionId(mission).executions().getFirst();
        List<Object> outcomes = race(() -> executionService.update(mission, row.getId(), update(row, ChecklistExecutionStatus.COMPLETED)));
        assertEquals(1, outcomes.stream().filter(ChecklistExecutionResponse.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.getErrorCode() == ErrorCode.CONCURRENT_UPDATE).count());
        assertEquals(1L, executionService.getByMissionId(mission).executions().getFirst().getVersion());
    }

    @Test void readinessChecksExpectedRowsAndAllowsUnableOrNonCompliant() {
        String mission = mission(3); var rows = executionService.getByMissionId(mission).executions();
        var nonCompliant = update(rows.get(0), ChecklistExecutionStatus.COMPLETED);
        nonCompliant.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
        executionService.update(mission, rows.get(0).getId(), nonCompliant);
        change(mission, rows.get(1), ChecklistExecutionStatus.COMPLETED);
        var inProgress = change(mission, rows.get(2), ChecklistExecutionStatus.IN_PROGRESS);
        assertFalse(executionService.getByMissionId(mission).readyForSubmission());
        change(mission, inProgress, ChecklistExecutionStatus.UNABLE_TO_VERIFY);
        assertTrue(executionService.getByMissionId(mission).readyForSubmission());
        assertEquals(MissionStatus.IN_FLIGHT, missions.findById(mission).orElseThrow().getStatus());
        new JdbcTemplate(dataSource).update("delete from mission_checklist_executions where id=?", rows.get(0).getId());
        assertFalse(executionService.getByMissionId(mission).readyForSubmission());
        executionService.getByMissionId(mission); assertEquals(2, executions.count());
    }

    @Test void identicalTerminalCommandsStillConsumeExpectedVersion() throws Exception {
        String mission = mission(1);
        var row = change(mission, executionService.getByMissionId(mission).executions().getFirst(), ChecklistExecutionStatus.COMPLETED);
        List<Object> outcomes = race(() -> executionService.update(mission, row.getId(), update(row, ChecklistExecutionStatus.COMPLETED)));
        assertEquals(1, outcomes.stream().filter(ChecklistExecutionResponse.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.getErrorCode() == ErrorCode.CONCURRENT_UPDATE).count());
        assertEquals(2L, executionService.getByMissionId(mission).executions().getFirst().getVersion());
    }

    @Test void submissionAndExecutionMutationSerializeOnSameMission() throws Exception {
        String mission = mission(1);
        var row = change(mission, executionService.getByMissionId(mission).executions().getFirst(), ChecklistExecutionStatus.COMPLETED);
        setStatus(mission, MissionStatus.COMPLETED);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        List<Object> outcomes = race(() -> calls.getAndIncrement() == 0
                ? executionService.update(mission, row.getId(), update(row, ChecklistExecutionStatus.COMPLETED))
                : resultService.upsert(mission, new MissionResultRequest()));
        assertEquals(1, outcomes.stream().filter(MissionResultResponse.class::isInstance).count());
        assertTrue(outcomes.stream().allMatch(outcome -> outcome instanceof MissionResultResponse
                || outcome instanceof ChecklistExecutionResponse
                || outcome instanceof ApiException error && error.getErrorCode() == ErrorCode.CHECKLIST_EXECUTION_LOCKED));
        assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, resultService.getByMissionId(mission).getApprovalStatus());
        var latest = executionService.getByMissionId(mission).executions().getFirst();
        error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> change(mission, latest, ChecklistExecutionStatus.IN_PROGRESS));
    }

    @Test void resultDraftSubmissionRejectionReopenAndApprovalPreserveExistingWorkflow() {
        String mission = mission(2); setStatus(mission, MissionStatus.PENDING_REVIEW);
        var reviewRows = executionService.getByMissionId(mission).executions();
        change(mission, reviewRows.getFirst(), ChecklistExecutionStatus.IN_PROGRESS);
        error(ErrorCode.MISSION_STATUS_INVALID, () -> resultService.upsert(mission, new MissionResultRequest()));
        setStatus(mission, MissionStatus.COMPLETED);
        transaction.executeWithoutResult(tx -> resultService.ensureCompletedResult(missions.findById(mission).orElseThrow()));
        var draft = resultService.getByMissionId(mission);
        assertEquals(MissionResultApprovalStatus.DRAFT, draft.getApprovalStatus()); assertNull(draft.getSubmittedAt());
        assertEquals(0, resultService.listPendingManagerApproval(0, 10).getTotalItems());
        error(ErrorCode.CHECKLIST_NOT_READY, () -> resultService.upsert(mission, new MissionResultRequest()));
        var rows = executionService.getByMissionId(mission).executions();
        var nonCompliant = update(rows.get(0), ChecklistExecutionStatus.COMPLETED);
        nonCompliant.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
        executionService.update(mission, rows.get(0).getId(), nonCompliant);
        change(mission, rows.get(1), ChecklistExecutionStatus.UNABLE_TO_VERIFY);
        var pending = resultService.upsert(mission, new MissionResultRequest());
        assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, pending.getApprovalStatus());
        var terminal = executionService.getByMissionId(mission).executions().getFirst();
        error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> change(mission, terminal, ChecklistExecutionStatus.IN_PROGRESS));
        resultService.reject(pending.getId(), new MissionResultReviewRequest());
        var reopened = change(mission, terminal, ChecklistExecutionStatus.IN_PROGRESS);
        error(ErrorCode.CHECKLIST_NOT_READY, () -> resultService.upsert(mission, new MissionResultRequest()));
        change(mission, reopened, ChecklistExecutionStatus.COMPLETED);
        var resubmitted = resultService.upsert(mission, new MissionResultRequest());
        assertEquals(pending.getId(), resubmitted.getId());
        var approved = resultService.approve(pending.getId(), new MissionResultReviewRequest());
        assertEquals(MissionResultApprovalStatus.APPROVED, approved.getApprovalStatus());
        // An approved result does not implicitly finalize the aggregate execution plan.
        transaction.executeWithoutResult(tx -> assertEquals(OrderStatus.APPROVED,
                missions.findById(mission).orElseThrow().getOrder().getOrderStatus()));
        error(ErrorCode.CHECKLIST_EXECUTION_LOCKED, () -> resultService.upsert(mission, new MissionResultRequest()));
    }

    @Test void resultGuardRequiresOperationalCompletionAndRechecksAtApproval() {
        String mission = mission(1); var row = executionService.getByMissionId(mission).executions().getFirst();
        change(mission, row, ChecklistExecutionStatus.COMPLETED);
        error(ErrorCode.MISSION_STATUS_INVALID, () -> resultService.upsert(mission, new MissionResultRequest()));
        setStatus(mission, MissionStatus.COMPLETED);
        var result = resultService.upsert(mission, new MissionResultRequest());
        new JdbcTemplate(dataSource).update("delete from mission_checklist_executions where id=?", row.getId());
        error(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID, () -> resultService.approve(result.getId(), new MissionResultReviewRequest()));
        assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, resultService.getByMissionId(mission).getApprovalStatus());
    }

    @Test void explicitEmptyResultCanSubmitAndApprove() {
        String mission = mission(0); setStatus(mission, MissionStatus.COMPLETED);
        var result = resultService.upsert(mission, new MissionResultRequest());
        assertEquals(MissionResultApprovalStatus.APPROVED,
                resultService.approve(result.getId(), new MissionResultReviewRequest()).getApprovalStatus());
    }


    @Configuration @Import(OrderMissionPostgresTest.MissionConfig.class)
    static class ExecutionConfig {
        @Bean IMissionResultService resultService(MissionResultRepository results, MissionRepository missions,
                OrderRepository orders, IMissionChecklistExecutionService executions, AuthenticatedUserResolver resolver,
                IMissionAuthorizationService auth, OrderChecklistItemRepository items,
                MissionChecklistAssignmentRepository assignments) {
            var media = mock(MediaAssetRepository.class);
            when(media.findByMissionIdOrderByCapturedAtDesc(anyString())).thenReturn(List.of());
            return new MissionResultService(results, missions, media, mock(IMediaAssetService.class),
                    org.mapstruct.factory.Mappers.getMapper(MissionResultMapper.class),
                    new com.ondemandmonitoring.order.service.impl.OrderExecutionCompletionPolicy(orders, missions, results, executions,
                            items, assignments, new com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService()),
                    executions, resolver, auth);
        }
    }
}
