package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Also inherits existing creation/snapshot regressions against the expanded Hibernate schema. */
@SpringJUnitConfig(OrderMissionPostgresTest.MissionConfig.class)
class OrderMultiMissionFoundationJpaPostgresTest extends OrderMissionPostgresTest {
    @Test void assignmentRepositoryPersistsLazyRelationsSoftHistoryAndOptimisticVersion() {
        String s = service("Monitoring");
        assignments.assign(s, assignment(checklist("Requirement"), 0));
        var order = orderService.createOrder(orderRequest(s));
        orderService.approveOrder(order.getId());
        String missionId = legacyMission(order.getId(), request(order.getId())).getId();
        var repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
                .getRepository(MissionChecklistAssignmentRepository.class);
        String itemId = order.getChecklistItems().getFirst().getId();
        String firstId = transaction.execute(tx -> {
            // Current historical fixture already includes its linked Assignment.
            return repository.findByMissionIdAndOrderChecklistItemIdAndRemovedAtIsNull(missionId,itemId).orElseThrow().getId();
        });
        transaction.executeWithoutResult(tx -> {
            var row = repository.findByMissionIdAndOrderChecklistItemIdAndRemovedAtIsNull(missionId, itemId).orElseThrow();
            assertEquals(firstId, row.getId()); assertEquals(0L, row.getVersion());
            assertFalse(org.hibernate.Hibernate.isInitialized(row.getMission()));
            assertFalse(org.hibernate.Hibernate.isInitialized(row.getOrderChecklistItem()));
            assertEquals(missionId, row.getMission().getId());
            assertEquals("Requirement", row.getOrderChecklistItem().getContent());
            row.setRemovedAt(Instant.now()); row.setRemovedBy(resolver.getCurrentUser().getId());
            row.setRemovalReason("Draft correction"); repository.saveAndFlush(row);
            assertEquals(1L, row.getVersion());
        });
        transaction.executeWithoutResult(tx -> {
            assertTrue(repository.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId).isEmpty());
            var next = new MissionChecklistAssignment();
            next.setMissionId(missionId); next.setOrderId(order.getId()); next.setOrderChecklistItemId(itemId);
            next.setAssignedAt(Instant.now()); repository.saveAndFlush(next);
            assertNotEquals(firstId, next.getId());
            assertEquals(2, repository.count());
            assertNotNull(repository.findById(firstId).orElseThrow().getRemovedAt());
        });
    }

    @Test void mandatoryExecutionLinkAndScopeMappingPreserveOptimisticVersion() {
        String s = service("Monitoring");
        assignments.assign(s, assignment(checklist("Requirement"), 0));
        var order = orderService.createOrder(orderRequest(s)); orderService.approveOrder(order.getId());
        String missionId = legacyMission(order.getId(), request(order.getId())).getId();
        var repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
                .getRepository(MissionChecklistExecutionRepository.class);
        String executionId = transaction.execute(tx -> {
            // Explicit historical fixture materialized this legacy execution.
            var execution = repository.findOrderedByMissionId(missionId).getFirst();
            assertNotNull(execution.getMissionChecklistAssignmentId());
            return execution.getId();
        });
        transaction.executeWithoutResult(tx -> {
            var execution = repository.findById(executionId).orElseThrow();
            assertEquals(0L, execution.getVersion());
            assertNotNull(execution.getMissionChecklistAssignmentId());
            assertEquals(missionId, execution.getMissionChecklistAssignment().getMissionId());
            var mission = missions.findById(missionId).orElseThrow();
            assertNull(mission.getReplacesMissionId());
            var persistedOrder = orders.findById(order.getId()).orElseThrow();
            assertEquals(ExecutionPlanStatus.DRAFT, persistedOrder.getExecutionPlan().getStatus());
            long oldVersion = persistedOrder.getVersion();
            persistedOrder.getExecutionPlan().setRevision(42); orders.saveAndFlush(persistedOrder);
            assertEquals(oldVersion + 1, persistedOrder.getVersion());
            assertEquals(42, persistedOrder.getExecutionPlan().getRevision());
            assertEquals(OrderStatus.APPROVED, persistedOrder.getOrderStatus());
        });
    }
}
