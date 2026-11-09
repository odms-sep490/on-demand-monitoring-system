package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPlanCoverageTest {
    final ExecutionPlanCoverageService service = new ExecutionPlanCoverageService();
    final Order order = new Order();
    final Mission first = new Mission(), second = new Mission();
    final List<OrderChecklistItem> items = new ArrayList<>();
    @BeforeEach void setup() {
        order.setId("o"); first.setId("m1"); second.setId("m2");
        for (var m : List.of(first,second)) { m.setOrder(order); m.setStatus(MissionStatus.PLANNING); }
        for (var id : List.of("A","B","C","D","E")) { var i=new OrderChecklistItem(); i.setId(id); i.setOrder(order); items.add(i); }
    }
    MissionChecklistAssignment assign(Mission m, String id) {
        var a=new MissionChecklistAssignment(); a.setId(m.getId()+id); a.setMissionId(m.getId()); a.setOrderId(order.getId());
        a.setOrderChecklistItemId(id); a.setOrderChecklistItem(items.stream().filter(i->i.getId().equals(id)).findFirst().orElseThrow()); return a;
    }
    List<MissionChecklistAssignment> all() { return List.of(assign(first,"A"),assign(first,"C"),assign(first,"E"),assign(second,"B"),assign(second,"D"),assign(second,"E")); }
    @Test void repeatedCoverageIsValid() {
        var c=service.evaluate(items,List.of(first,second),all());
        assertTrue(c.coverageReady()); assertEquals(5,c.covered()); assertEquals(1,c.repeated());
    }
    @Test void exactlyOnceAndMissingAreDistinct() {
        var rows=new ArrayList<>(all()); rows.removeLast(); assertTrue(service.evaluate(items,List.of(first,second),rows).coverageReady());
        rows.remove(4); var c=service.evaluate(items,List.of(first,second),rows);
        assertFalse(c.coverageReady()); assertEquals(List.of("D"),c.uncoveredItemIds());
    }
    @Test void removedAndCancelledAssignmentsDoNotCover() {
        var rows=all(); rows.get(4).setRemovedAt(java.time.Instant.now());
        assertEquals(1,service.evaluate(items,List.of(first,second),rows).uncovered());
        second.setStatus(MissionStatus.CANCELLED);
        assertEquals(2,service.evaluate(items,List.of(first,second),all()).uncovered());
    }
    @Test void emptySnapshotVacuouslyCovered() { assertTrue(service.evaluate(List.of(),List.of(first,second),List.of()).coverageReady()); }
    @Test void crossOrderRejected() {
        var rows=all(); rows.getFirst().setOrderId("other");
        assertEquals(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER,assertThrows(ApiException.class,()->service.evaluate(items,List.of(first,second),rows)).getErrorCode());
    }
    @Test void duplicateWithinMissionRejected() {
        var rows=new ArrayList<>(all()); rows.add(assign(first,"A"));
        assertEquals(ErrorCode.CHECKLIST_ASSIGNMENT_DUPLICATE,assertThrows(ApiException.class,()->service.evaluate(items,List.of(first,second),rows)).getErrorCode());
    }
}
