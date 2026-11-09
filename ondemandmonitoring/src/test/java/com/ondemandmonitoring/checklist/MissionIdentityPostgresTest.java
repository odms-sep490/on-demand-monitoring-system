package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.order.enums.OrderStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Current 1:N schema supports sibling identity and deterministic query ordering. */
class MissionIdentityPostgresTest extends OrderMissionPostgresTest {
    @Test void aggregateQuerySupportsZeroOneAndMultipleChildrenWithStableOrdering() {
        String orderId = order(OrderStatus.APPROVED);
        assertTrue(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).isEmpty());
        var first = missionService.createMission(orderId, request(orderId));
        assertEquals(java.util.List.of(first.getId()), missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId)
                .stream().map(m -> m.getId()).toList());
        transaction.executeWithoutResult(tx -> {
            var jdbc = new JdbcTemplate(dataSource);
            // Only fixture rows are rolled back; no schema gate needs to be removed.
            jdbc.update("UPDATE missions SET scheduled_start_at=? WHERE id=?",
                    java.sql.Timestamp.from(Instant.parse("2026-10-08T10:00:00Z")), first.getId());
            for (String id : java.util.List.of("sibling-b", "sibling-a")) {
                jdbc.update("""
                        INSERT INTO missions(id,mission_code,order_id,status,scheduled_start_at,scheduled_end_at,created_at,version)
                        VALUES (?, ?, ?, 'PLANNING', '2026-10-08T09:00:00Z', '2026-10-08T11:00:00Z', now(), 0)
                        """, id, id, orderId);
            }
            assertEquals(java.util.List.of("sibling-a", "sibling-b", first.getId()),
                    missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).stream().map(m -> m.getId()).toList());
            assertEquals("sibling-b", missions.findById("sibling-b").orElseThrow().getId());
            assertTrue(missions.findById(orderId).isEmpty());
            tx.setRollbackOnly();
        });
        assertEquals(1, count(orderId));
    }
}
