package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.mission.domain.*;
import java.util.Objects;

/** Compare every scalar/composite identity; never accept an Assignment from a sibling. */
public final class ChecklistAssignmentIntegrity {
    private ChecklistAssignmentIntegrity() {}

    public static boolean matches(Mission mission, MissionChecklistAssignment assignment) {
        return mission != null && mission.getOrder() != null && assignment != null && assignment.getId() != null
                && assignment.getRemovedAt() == null && Objects.equals(mission.getId(), assignment.getMissionId())
                && Objects.equals(mission.getOrder().getId(), assignment.getOrderId())
                && assignment.getOrderChecklistItem() != null && assignment.getOrderChecklistItem().getOrder() != null
                && Objects.equals(assignment.getOrderChecklistItemId(), assignment.getOrderChecklistItem().getId())
                && Objects.equals(assignment.getOrderId(), assignment.getOrderChecklistItem().getOrder().getId());
    }

    public static boolean matches(Mission mission, MissionChecklistExecution execution) {
        if (execution == null || !matches(mission, execution.getMissionChecklistAssignment())) return false;
        var assignment = execution.getMissionChecklistAssignment();
        return Objects.equals(execution.getMissionChecklistAssignmentId(), assignment.getId())
                && Objects.equals(execution.getMissionId(), assignment.getMissionId())
                && Objects.equals(execution.getOrderId(), assignment.getOrderId())
                && Objects.equals(execution.getOrderChecklistItemId(), assignment.getOrderChecklistItemId())
                && execution.getOrderChecklistItem() != null && execution.getOrderChecklistItem().getOrder() != null
                && Objects.equals(execution.getOrderChecklistItemId(), execution.getOrderChecklistItem().getId())
                && Objects.equals(execution.getOrderId(), execution.getOrderChecklistItem().getOrder().getId());
    }
}
