package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.mission.domain.MissionResult;
import com.ondemandmonitoring.mission.dto.request.MissionResultReviewRequest;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionResultMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.order.service.impl.OrderExecutionCompletionPolicy;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual Phase 5 assignments, evidence, PostgreSQL row locks and transactional approval. */
@SpringJUnitConfig(OrderAggregateCompletionPostgresTest.Beans.class)
class OrderAggregateCompletionPostgresTest extends MissionChecklistCutoverPostgresTest {
    @Autowired IMissionResultService resultService;
    @Autowired MissionResultRepository resultRepository;
    @Autowired IOrderExecutionCompletionPolicy completion;

    void ready(int items) {
        fixture(items);
        if (items > 0) { choose(first, 0); choose(second, 0); }
        finalizePlan();
        jdbc.update("update missions set status='COMPLETED',completed_at=now() where order_id=?", orderId);
        jdbc.update("update mission_checklist_executions set execution_status='UNABLE_TO_VERIFY',unable_to_verify_reason='Cloud cover',started_at=now(),completed_at=now() where order_id=?", orderId);
    }
    String result(String mission, MissionResultApprovalStatus approval) {
        return transaction.execute(tx -> {
            var result = new MissionResult(); result.setMission(missions.findById(mission).orElseThrow());
            result.setStatus(MissionResultStatus.COMPLETED); result.setApprovalStatus(approval);
            result.setCompletedAt(Instant.now()); result.setMediaCount(0);
            if (approval == MissionResultApprovalStatus.APPROVED) result.setApprovedAt(Instant.now());
            return resultRepository.saveAndFlush(result).getId();
        });
    }
    OrderStatus status() { return OrderStatus.valueOf(jdbc.queryForObject("select order_status from orders where id=?", String.class, orderId)); }
    long orderVersion() { return jdbc.queryForObject("select version from orders where id=?", Long.class, orderId); }
    boolean evaluate() { return transaction.execute(tx -> completion.completeIfReady(completion.lockAggregate(orderId))); }

    @Test void singleEffectiveApprovedMissionCompletes() {
        ready(1); jdbc.update("update missions set status='CANCELLED',cancellation_at=now() where id=?", second);
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void firstApprovalDoesNotCompleteButSecondDoes() {
        ready(1); var a = result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        var b = result(second, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        long version = orderVersion(); resultService.approve(a, null);
        assertEquals(OrderStatus.APPROVED, status()); assertEquals(version, orderVersion());
        resultService.approve(b, null); assertEquals(OrderStatus.COMPLETED, status()); assertEquals(version + 1, orderVersion());
    }
    @Test void failedSiblingBlocksDespiteAnApprovedResult() {
        ready(0); result(second, MissionResultApprovalStatus.APPROVED);
        jdbc.update("update missions set status='FAILED' where id=?", second);
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.APPROVED, status());
    }
    @Test void missingSiblingResultBlocks() {
        ready(0); resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.APPROVED, status());
    }

    @Test void finalizedPlanWithUncoveredSnapshotCannotCompleteAggregate() {
        ready(0);
        // Persist an inconsistent historical snapshot through JPA in this disposable fixture.
        // FINALIZED alone is not proof that every Order requirement has effective coverage.
        transaction.executeWithoutResult(tx -> {
            var item = new com.ondemandmonitoring.order.domain.OrderChecklistItem();
            item.setOrder(orders.findById(orderId).orElseThrow());
            item.setContent("Uncovered historical requirement");
            item.setReviewStatus(com.ondemandmonitoring.order.enums.ChecklistReviewStatus.PENDING);
            item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
            item.setEvidencePolicyVersion(1);
            item.setMinimumEvidenceCount(1);
            item.setRequiredEvidenceType(com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE_OR_VIDEO);
            snapshots.saveAndFlush(item);
        });
        result(first, MissionResultApprovalStatus.APPROVED);
        result(second, MissionResultApprovalStatus.APPROVED);
        assertEquals(1, plan.get(orderId).coverage().uncovered());
        assertFalse(evaluate());
        assertEquals(OrderStatus.APPROVED, status());
    }
    @Test void rejectedSiblingResultBlocks() {
        ready(0); result(second, MissionResultApprovalStatus.REJECTED);
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.APPROVED, status());
    }
    @Test void incompleteMissionBlocksEvenWhenResultIsApproved() {
        ready(0); result(second, MissionResultApprovalStatus.APPROVED);
        jdbc.update("update missions set status='PENDING_REVIEW' where id=?", second);
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.APPROVED, status());
    }
    @Test void previouslyApprovedSiblingChecklistIsRechecked() {
        ready(1); result(first, MissionResultApprovalStatus.APPROVED);
        jdbc.update("update mission_checklist_executions set execution_status='PENDING',unable_to_verify_reason=null,started_at=null,completed_at=null where mission_id=?", first);
        resultService.approve(result(second, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.APPROVED, status());
    }
    @Test void pendingEvidenceOnPreviouslyApprovedSiblingBlocksCompletionThenReplayRetriggers() {
        ready(1); String media = asset(first);
        jdbc.update("update mission_checklist_executions set execution_status='COMPLETED',unable_to_verify_reason=null where mission_id=?", first);
        var execution = runtime.getByMissionId(first).executions().getFirst();
        evidence.attach(first, new com.ondemandmonitoring.mission.dto.request.BatchChecklistEvidenceRequest(media,
                java.util.List.of(new com.ondemandmonitoring.mission.dto.request.BatchChecklistEvidenceRequest.Target(execution.getId(), execution.getVersion())), null));
        result(first, MissionResultApprovalStatus.APPROVED);
        String b = result(second, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        resultService.approve(b, null); assertEquals(OrderStatus.APPROVED, status());
        jdbc.update("update drone_media set media_status='AVAILABLE',available_at=now() where id=?", media);
        resultService.approve(b, null); assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void currentResultCannotApproveWithoutFinalEvidenceAndRollsBack() {
        ready(1); jdbc.update("update mission_checklist_executions set execution_status='COMPLETED',unable_to_verify_reason=null where mission_id=?", first);
        String a = result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        error(ErrorCode.CHECKLIST_NOT_READY, () -> resultService.approve(a, null));
        transaction.executeWithoutResult(tx -> assertEquals(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL, resultRepository.findById(a).orElseThrow().getApprovalStatus()));
        assertEquals(OrderStatus.APPROVED, status());
    }
    @Test void repeatedChecklistAcrossSiblingsHasIndependentExecutionsAndCompletes() {
        ready(1); assertEquals(2, rows("mission_checklist_executions"));
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.APPROVED, status());
        resultService.approve(result(second, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void emptyChecklistOrderCanCompleteAfterBothApprovals() {
        ready(0); assertEquals(0, rows("mission_checklist_executions"));
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        resultService.approve(result(second, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void nonCompliantAndUnableToVerifyDoNotBlockServiceCompletion() {
        ready(1); jdbc.update("update mission_checklist_executions set assessment_status='NON_COMPLIANT' where order_id=?", orderId);
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        resultService.approve(result(second, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void completedNonCompliantChecklistWithApprovedEvidenceCanComplete() {
        ready(1);
        for (String mission : java.util.List.of(first, second)) {
            String media = asset(mission);
            jdbc.update("update drone_media set media_status='AVAILABLE',available_at=now() where id=?", media);
            jdbc.update("update mission_checklist_executions set execution_status='COMPLETED',unable_to_verify_reason=null,assessment_status='NON_COMPLIANT' where mission_id=?", mission);
            var execution = runtime.getByMissionId(mission).executions().getFirst();
            evidence.attach(mission, new com.ondemandmonitoring.mission.dto.request.BatchChecklistEvidenceRequest(media,
                    java.util.List.of(new com.ondemandmonitoring.mission.dto.request.BatchChecklistEvidenceRequest.Target(execution.getId(), execution.getVersion())), null));
            resultService.approve(result(mission, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        }
        assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void getOrderAndCustomerListsNeverMutateEvenWithAllResultsApproved() {
        ready(0); result(first, MissionResultApprovalStatus.APPROVED); result(second, MissionResultApprovalStatus.APPROVED);
        long version = orderVersion();
        assertEquals(OrderStatus.APPROVED, orderService.getOrderById(orderId).getOrderStatus());
        assertEquals(OrderStatus.APPROVED, orderService.getMyOrders(null).getFirst().getOrderStatus());
        assertEquals(OrderStatus.APPROVED, orderService.getMyOrders(OrderStatus.APPROVED).getFirst().getOrderStatus());
        assertEquals(version, orderVersion()); assertEquals(OrderStatus.APPROVED, status());
    }
    @Test void simultaneousFinalApprovalsSerializeAndCompleteExactlyOnce() throws Exception {
        ready(0); String a = result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        String b = result(second, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        long version = orderVersion(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return resultService.approve(a, null); });
            var two = pool.submit(() -> { start.await(); return resultService.approve(b, null); });
            start.countDown(); one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        }
        assertEquals(OrderStatus.COMPLETED, status()); assertEquals(version + 1, orderVersion());
        assertEquals(2, jdbc.queryForObject("select count(*) from mission_results where approval_status='APPROVED'", Integer.class));
    }
    @Test void approveReplayPreservesAuditAndVersions() {
        ready(0); result(second, MissionResultApprovalStatus.APPROVED);
        String a = result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        var request = new MissionResultReviewRequest(); request.setNote("Original review");
        resultService.approve(a, request);
        var before = jdbc.queryForMap("select approved_at,reviewed_by,review_note,version from mission_results where id=?", a);
        long version = orderVersion(); request.setNote("Do not overwrite"); resultService.approve(a, request);
        assertEquals(before, jdbc.queryForMap("select approved_at,reviewed_by,review_note,version from mission_results where id=?", a));
        assertEquals(version, orderVersion());
    }
    @Test void cancelledFailedSiblingCanBeExcludedOnApprovalReplay() {
        ready(0); jdbc.update("update missions set status='FAILED' where id=?", second);
        String a = result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        resultService.approve(a, null); assertEquals(OrderStatus.APPROVED, status());
        // Fixture only: cancellation/replacement commands are intentionally not part of Phase 6A.
        jdbc.update("update missions set status='CANCELLED',cancellation_at=now() where id=?", second);
        resultService.approve(a, null); assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void legacySingleMissionStillRequiresOperationalResultAndChecklistReadiness() {
        ready(1); jdbc.update("delete from mission_checklist_executions where mission_id=?", second);
        jdbc.update("delete from mission_checklist_assignments where mission_id=?", second);
        jdbc.update("delete from missions where id=?", second);
        jdbc.update("update missions set finalized_plan_revision=null where id=?", first);
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.COMPLETED, status());
    }
    @Test void inProgressOrderRemainsInProgressUntilAggregateReady() {
        ready(0); jdbc.update("update orders set order_status='IN_PROGRESS' where id=?", orderId);
        resultService.approve(result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL), null);
        assertEquals(OrderStatus.IN_PROGRESS, status());
    }
    void legacyDraftPlan() {
        jdbc.update("update missions set finalized_plan_revision=null where order_id=?", orderId);
        jdbc.update("update orders set execution_plan_status='DRAFT',execution_plan_finalized_at=null,execution_plan_finalized_by=null where id=?", orderId);
    }
    @Test void draftLegacyAggregateCannotCompleteDespiteApprovedResultsAndRealFinalReadiness() {
        ready(1); legacyDraftPlan();
        result(first, MissionResultApprovalStatus.APPROVED); result(second, MissionResultApprovalStatus.APPROVED);
        transaction.executeWithoutResult(tx -> {
            assertTrue(runtime.isReadyForFinalApproval(missions.findById(first).orElseThrow()));
            assertTrue(runtime.isReadyForFinalApproval(missions.findById(second).orElseThrow()));
        });
        long version = orderVersion(); assertFalse(evaluate());
        assertEquals(OrderStatus.APPROVED, status()); assertEquals(version, orderVersion());
    }
    @Test void draftLegacyApprovalReplayAndGetPreserveApprovedResultWithoutCompletingOrder() {
        ready(1); jdbc.update("delete from mission_checklist_executions where mission_id=?", second);
        jdbc.update("delete from mission_checklist_assignments where mission_id=?", second);
        jdbc.update("delete from missions where id=?", second); legacyDraftPlan();
        String a = result(first, MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        long version = orderVersion(); resultService.approve(a, null);
        var audit = jdbc.queryForMap("select approval_status,approved_at,reviewed_by,review_note,version from mission_results where id=?", a);
        assertEquals("APPROVED", audit.get("approval_status"));
        resultService.approve(a, null); orderService.getOrderById(orderId); orderService.getMyOrders(null);
        assertEquals(audit, jdbc.queryForMap("select approval_status,approved_at,reviewed_by,review_note,version from mission_results where id=?", a));
        assertEquals(OrderStatus.APPROVED, status()); assertEquals(version, orderVersion());
    }

    @Configuration @Import(MissionChecklistCutoverPostgresTest.Beans.class)
    static class Beans {
        @Bean IOrderExecutionCompletionPolicy completion(OrderRepository orders, MissionRepository missions,
                MissionResultRepository results, IMissionChecklistExecutionService checklist,
                com.ondemandmonitoring.order.repository.OrderChecklistItemRepository items,
                MissionChecklistAssignmentRepository assignments) {
            return new OrderExecutionCompletionPolicy(orders, missions, results, checklist, items, assignments,
                    new com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService());
        }
        @Bean IMissionResultService aggregateResults(MissionResultRepository results, MissionRepository missions,
                MediaAssetRepository media, IOrderExecutionCompletionPolicy completion,
                IMissionChecklistExecutionService checklist, AuthenticatedUserResolver resolver, IMissionAuthorizationService auth) {
            var mapper = Mappers.getMapper(MissionResultMapper.class);
            org.springframework.test.util.ReflectionTestUtils.setField(mapper, "mediaAssetMapper",
                    Mappers.getMapper(com.ondemandmonitoring.media.mapper.MediaAssetMapper.class));
            return new MissionResultService(results, missions, media, mock(IMediaAssetService.class),
                    mapper, completion, checklist, resolver, auth);
        }
    }
}
