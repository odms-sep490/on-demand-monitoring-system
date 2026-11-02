package com.ondemandmonitoring.mission.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import jakarta.persistence.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderMultiMissionFoundationTest {
    @Test void orderDefaultsDoNotReuseOptimisticVersion() throws Exception {
        for (Order order : new Order[] {new Order(), Order.builder().build()}) {
            assertEquals(ExecutionPlanStatus.DRAFT, order.getExecutionPlanStatus());
            assertEquals(0, order.getExecutionPlanRevision());
            assertNull(order.getVersion());
            order.setVersion(8L);
            order.setExecutionPlanRevision(3);
            assertEquals(8L, order.getVersion());
        }
        assertNotNull(BaseEntity.class.getDeclaredField("version").getAnnotation(Version.class));
    }

    @Test void relationAllowsMultiMissionWithoutHibernateRecreatingPhaseTwoGate() throws Exception {
        var field = Mission.class.getDeclaredField("order");
        assertEquals(FetchType.LAZY, field.getAnnotation(ManyToOne.class).fetch());
        assertFalse(field.getAnnotation(ManyToOne.class).optional());
        assertFalse(field.getAnnotation(JoinColumn.class).nullable());
        assertFalse(field.getAnnotation(JoinColumn.class).unique());
        assertFalse(java.util.Arrays.stream(Mission.class.getAnnotation(Table.class).uniqueConstraints())
                .anyMatch(c -> c.name().equals("uk_missions_order")));
    }

    @Test void newMissionFieldsAreNullableForUnchangedRuntime() {
        var mission = new Mission();
        assertNull(mission.getReplacesMissionId());
        assertNull(mission.getFlightPermit());
        assertEquals(FlightPermitStatus.PENDING, FlightPermit.pendingFor(mission).getStatus());
    }

    @Test void flightPermitIsASeparateOneToOneAggregate() throws Exception {
        var missionField = Mission.class.getDeclaredField("flightPermit");
        assertEquals("mission", missionField.getAnnotation(OneToOne.class).mappedBy());
        assertEquals(FetchType.LAZY, missionField.getAnnotation(OneToOne.class).fetch());

        var mission = new Mission();
        var permit = FlightPermit.pendingFor(mission);
        assertSame(mission, permit.getMission());
        assertSame(permit, mission.getFlightPermit());
    }

    @Test void executionAssignmentIsMandatoryAndExistingIdentityRemains() throws Exception {
        assertNull(new MissionChecklistExecution().getMissionChecklistAssignmentId());
        assertFalse(MissionChecklistExecution.class.getDeclaredField("missionChecklistAssignment")
                .getAnnotation(OneToOne.class).optional());
        assertFalse(MissionChecklistExecution.class.getDeclaredField("missionChecklistAssignmentId").getAnnotation(Column.class).nullable());
        for (String name : new String[] {"missionId", "orderId", "orderChecklistItemId"}) {
            assertFalse(MissionChecklistExecution.class.getDeclaredField(name).getAnnotation(Column.class).nullable());
        }
    }

    @Test void negativePlanningValuesFailValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var order = new Order(); order.setExecutionPlanRevision(-1);
            assertTrue(factory.getValidator().validate(order).stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("executionPlanRevision")));
            var assignment = new MissionChecklistAssignment(); assignment.setDisplayOrder(-1);
            assertFalse(factory.getValidator().validate(assignment).isEmpty());
        }
    }
}
